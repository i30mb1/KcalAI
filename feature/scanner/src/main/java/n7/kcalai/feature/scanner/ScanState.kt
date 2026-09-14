package n7.kcalai.feature.scanner

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import kotlin.math.roundToInt
import n7.kcalai.model.Nutriments
import n7.kcalai.repositories.BarcodeValidator
import n7.kcalai.repositories.LabelReading
import n7.kcalai.repositories.NutrimentValidator

/**
 * Одно поле карточки продукта.
 *
 * Три состояния вместо двух, и третье — главное. Поле может быть пустым, может
 * быть заполнено распознаванием, а может быть заполнено человеком; в последнем
 * случае камера в него больше не пишет. Без этого разделения набранное руками
 * затирал бы следующий же кадр — ровно в тот момент, когда человек решил, что
 * камера ошибается, и взялся печатать сам.
 *
 * [rejected] — то, что человек снял крестиком. Помнить это обязательно: камера
 * продолжает видеть ту же пачку и через секунду предложит снятое значение снова,
 * и крестик без памяти не делал бы ничего.
 */
@Stable
internal class ScanField(initial: String = "", manual: Boolean = false) {

    var text by mutableStateOf(initial)
        private set

    /** Значение набрано человеком — распознавание его не трогает. */
    var manual by mutableStateOf(manual)
        private set

    /** Значение, снятое крестиком. Видно снаружи: это прямая разметка ошибки разбора. */
    var rejected by mutableStateOf<String?>(null)
        private set

    /** Распознавание предлагает значение. Молча уступает человеку — и снятому. */
    fun offer(value: String?) {
        if (manual || value.isNullOrEmpty() || value == rejected) return
        text = value
    }

    fun type(input: String) {
        text = input
        // Пустое поле снова отдаётся камере: стереть всё и уйти — это не «я ввёл
        // пустоту», а «я передумал вводить». Тупика из-за этого быть не должно.
        manual = input.isNotEmpty()
    }

    /** Крестик. Значение уходит и само собой не возвращается. */
    fun clear() {
        rejected = text.takeIf(String::isNotEmpty) ?: rejected
        text = ""
        manual = false
    }

    val filled: Boolean get() = text.isNotEmpty()
}

/**
 * Состояние экрана съёмки.
 *
 * Экран стал карточкой продукта, которую заполняет камера, а не окном, после
 * которого открывается форма. Поэтому состояние живёт здесь целиком: и то, что
 * пришло из кадров, и то, что поправил человек.
 */
@Stable
internal class ScanState(initialGtin: String?) {

    val name = ScanField()
    val kcal = ScanField()
    val prot = ScanField()
    val fat = ScanField()
    val carb = ScanField()
    val serving = ScanField()

    /**
     * Код, с промаха которого пришли, считается введённым человеком: он его
     * отсканировал сам, и подменять его случайно пойманным в кадре чужим кодом
     * с соседней пачки нельзя.
     */
    val barcode = ScanField(initialGtin.orEmpty(), manual = initialGtin != null)

    var frame by mutableStateOf(LabelFrame(LabelReading.EMPTY, 0))
        private set

    /**
     * Сколько кадров прошло через распознавание.
     *
     * Не то же, что окно согласия: то держит последние восемь и стоит на восьми.
     * Здесь нужен растущий счётчик — он единственное на экране, что доказывает,
     * что съёмка идёт, пока ни одно значение ещё не набралось.
     */
    var seen by mutableIntStateOf(0)
        private set

    /** Человек нажал «Сохранить». Нужно диагностике: см. [outcome]. */
    var saved by mutableStateOf(false)
        private set

    fun onSaved() {
        saved = true
    }

    /** Голоса по ключам. Считается один раз на кадр: [progress] зовут по десятку раз на перерисовку. */
    private var votes by mutableStateOf<Map<String, LabelConsensus.Field>>(emptyMap())

    fun onFrame(next: LabelFrame) {
        frame = next
        if (next.available) seen++

        val fields = next.fields.toMap()
        votes = fields

        // В поля уходит только набранное голосами, и берётся оно у самого
        // голосования, а не из черновика кадра. Черновик, пока четвёрка
        // не сошлась целиком, показывает последний кадр как есть — и когда
        // человек уже отвёл камеру на полку, это ценник «2.49 5.53», разобранный
        // арифметикой. Поле белков при этом всё ещё считалось набранным:
        // три кадра с этикетки из окна не ушли, — и в него уезжало 5,5 с ценника
        // вместо нуля, за который кадры голосовали.
        kcal.offer(fields["ккал"]?.settledValue?.toString())
        prot.offer(fields["Б"]?.settledValue.centigramsToField())
        fat.offer(fields["Ж"]?.settledValue.centigramsToField())
        carb.offer(fields["У"]?.settledValue.centigramsToField())

        // Название не пересматривается на каждом кадре, в отличие от чисел,
        // и это не мелочь, а разница в природе величины. Числа на этикетке
        // напечатаны в одной таблице: пока камера смотрит на пачку, она видит
        // их все и голосует за них разом. Название же стоит на другой стороне
        // упаковки, и как только человек переводит камеру на таблицу, окно
        // согласия за пять секунд забывает его целиком — а голоса набирает
        // то, что осталось в кадре: «Масса», «Хранить», обрывок состава.
        // Прочитанное название так затиралось служебной надписью ровно в тот
        // момент, когда человек уводил камеру, чтобы дочитать КБЖУ.
        //
        // Поэтому первое подтверждённое остаётся. Человек не заперт: крестик
        // освобождает поле и помнит снятое, а чипсы под полем держат остальных
        // кандидатов — включая тот, что камера видит прямо сейчас.
        if (!name.filled) name.offer(next.name)
    }

    fun onBarcode(code: String) {
        barcode.offer(code)
    }

    fun progress(key: String): Float = when {
        field(key).manual -> 1f
        field(key).filled -> 1f
        else -> votes[key]?.progress ?: 0f
    }

    private fun field(key: String): ScanField = when (key) {
        "ккал" -> kcal
        "Б" -> prot
        "Ж" -> fat
        else -> carb
    }

    /** Названия-кандидаты с пачки, уже отсортированные голосованием кадров. */
    val nameChoices: List<String> get() = frame.names

    /** Числа, распознанные на этикетке, — чтобы не набирать «12,4» с клавиатуры. */
    val numbers: List<String> get() = frame.reading.numbers

    val nutriments: Nutriments?
        get() = kcal.text.toIntOrNull()?.let {
            // Незаполненный макрос — это ноль, а не отказ сохранять: у растительного
            // масла белков действительно нет, и печатать «0» человека заставлять незачем.
            Nutriments(
                kcal100 = it,
                prot100 = prot.text.toCentigrams() ?: 0,
                fat100 = fat.text.toCentigrams() ?: 0,
                carb100 = carb.text.toCentigrams() ?: 0,
            )
        }

    val check get() = nutriments?.let(NutrimentValidator::check)

    /** Код уходит в сохранение только пройдя контрольную цифру. */
    val gtin: String? get() = BarcodeValidator.normalize(barcode.text)

    val servingG: Int? get() = serving.text.toIntOrNull()?.takeIf { it > 0 }

    /** Название — единственное, чего нельзя ни вычислить, ни проверить арифметикой. */
    val canSave: Boolean get() = name.text.isNotBlank() && check?.valid == true

    /** Сколько из шести полей заполнено — для кольца и счётчика. */
    val filledFields: Int
        get() = listOf(kcal, prot, fat, carb, name, barcode).count { it.filled }

    val requiredFilled: Int get() = listOf(kcal, prot, fat, carb).count { it.filled }

    /** Кольцо считает обязательную четвёрку: без неё сохранять нечего. */
    val overall: Float
        get() = REQUIRED.map(::progress).average().toFloat()

    /**
     * Чем кончилась съёмка — последняя и самая ценная строка диагностики.
     *
     * Кадры показывают, что видел распознаватель. Эта строка показывает, где он
     * ошибся: рядом стоит то, что предложила камера, и то, что человек оставил
     * в поле. Расхождение и есть готовая разметка — по ней правится разбор,
     * а без неё остаётся гадать, почему съёмку бросили.
     */
    fun outcome(saved: Boolean): String = buildString {
        append(if (saved) "сохранено" else "закрыто без сохранения")
        line("название", name)
        line("ккал", kcal)
        line("Б", prot)
        line("Ж", fat)
        line("У", carb)
        line("порция", serving)
        line("код", barcode)
    }

    private fun StringBuilder.line(label: String, field: ScanField) {
        if (!field.filled && field.rejected == null) return

        append("\n ").append(label).append(": ")
        append(field.text.ifEmpty { "—" })
        append(
            when {
                // Самый интересный случай: камера прочитала своё, человек стёр
                // и написал другое. Здесь виден и её ответ, и верный.
                field.rejected != null && field.manual ->
                    " (руками, камера читала «${field.rejected}»)"
                field.rejected != null -> " (снято крестиком, камера читала «${field.rejected}»)"
                field.manual -> " (руками)"
                else -> " (камера)"
            }
        )
    }

    companion object {
        val REQUIRED = listOf("ккал", "Б", "Ж", "У")
    }
}

/**
 * Состояние переживает поворот экрана.
 *
 * Не роскошь: съёмка идёт с пачкой в руках, телефон в ней же, и случайный
 * поворот, стирающий набранное вручную, обесценивает всю затею.
 */
@Composable
internal fun rememberScanState(initialGtin: String?): ScanState =
    rememberSaveable(initialGtin, saver = ScanStateSaver) { ScanState(initialGtin) }

private val ScanStateSaver: Saver<ScanState, Any> = listSaver(
    save = { state ->
        listOf(
            state.name.text, state.kcal.text, state.prot.text,
            state.fat.text, state.carb.text, state.serving.text, state.barcode.text,
        )
    },
    restore = { saved ->
        ScanState(saved.getOrNull(6)?.takeIf { it.isNotEmpty() }).apply {
            // Восстановленное считается набранным человеком: после поворота
            // камера начнёт читать заново, и затирать пережившее поворот нельзя.
            name.type(saved[0])
            kcal.type(saved[1])
            prot.type(saved[2])
            fat.type(saved[3])
            carb.type(saved[4])
            serving.type(saved[5])
        }
    },
)

/** Сотые грамма в то, что человек ожидает увидеть в поле: «12,4», но «5», а не «5,0». */
private fun Int?.centigramsToField(): String? {
    if (this == null) return null
    return if (this % 100 == 0) (this / 100).toString() else "${this / 100},${(this % 100) / 10}"
}

/** Граммы из поля в сотые грамма модели. Запятая и точка равноправны — клавиатуры разные. */
internal fun String.toCentigrams(): Int? =
    replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 100).roundToInt() }
