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
import kotlin.time.TimeSource
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
internal class ScanState(
    initialGtin: String?,
    /** Миллисекунды с открытия карточки. Свои, а не системные: тест двигает их сам. */
    private val clock: () -> Long = monotonicClock(),
) {

    val name = ScanField()
    val kcal = ScanField()
    val prot = ScanField()
    val fat = ScanField()
    val carb = ScanField()

    /**
     * Код, с промаха которого пришли, считается введённым человеком: он его
     * отсканировал сам, и подменять его случайно пойманным в кадре чужим кодом
     * с соседней пачки нельзя.
     */
    val barcode = ScanField(initialGtin.orEmpty(), manual = initialGtin != null)

    /** Карточка открылась с промаха скана: товара нет ни в одной базе. */
    val fromMiss: Boolean = initialGtin != null

    /**
     * Числа в порядке строк этикетки: белки, жиры, углеводы — и калории.
     * В этом порядке их и вписывают: глаза идут по пачке сверху вниз.
     */
    val numberFields: List<ScanField> get() = listOf(prot, fat, carb, kcal)

    /**
     * Камера не справилась — персонажи просят вписать руками. Или человек сам
     * взялся вписывать числа: тогда просьба та же, а камера по-прежнему читает.
     */
    var manual by mutableStateOf(false)
        private set

    /**
     * Поле, которое сейчас набирают: числа и код — цифровой панелью, название —
     * клавиатурой. `null` — панели нет. Камера в это поле не пишет.
     */
    var typing by mutableStateOf<ScanField?>(null)
        private set

    /** Когда в последний раз заполненных полей прибавилось — с камеры или руками. */
    private var calmSince = clock()
    private var lastFilled = 0

    /** С какого кадра камера мылит без перерыва. `null` — последний кадр резкий. */
    private var blurSince: Long? = null

    /**
     * Пора ли просить вписать руками. Зовётся раз в секунду, пока карточка открыта.
     *
     * Двадцать секунд — без нового числа, а не с открытия карточки: камеру, которая
     * читает медленно, но читает, не перебивают. Мыло — раньше: распознавание мыльный
     * кадр пропускает, и шесть секунд одного мыла значат, что ждать нечего.
     */
    fun tick() {
        noteProgress()
        // Человек сам что-то набирает — название, например: цифры посреди слова
        // спрятали бы клавиатуру у него из-под пальцев.
        if (manual || typing != null) return
        val first = firstMissingNumber() ?: return
        val now = clock()
        val stalled = now - calmSince >= STALL_MS
        val blurred = blurSince?.let { now - it >= BLUR_MS } ?: false
        if (stalled || blurred) {
            manual = true
            typing = first
        }
    }

    /** Камеры нет или распознавать нечем — просить вписать сразу. */
    fun withoutCamera() {
        if (manual) return
        manual = true
        typing = firstMissingNumber()
    }

    /** Тап по полю. Число — значит, человек взялся вписывать сам, и панель с цифрами встаёт. */
    fun startTyping(field: ScanField) {
        typing = field
        if (field in numberFields) manual = true
    }

    /**
     * Цифры спрятаны: человек хочет ещё раз камерой. Она получает свои двадцать
     * секунд заново — иначе просьба вернулась бы через мгновение.
     */
    fun stopTyping() {
        typing = null
        manual = false
        calmSince = clock()
        blurSince = null
    }

    /** Кнопка цифровой панели — в поле, которое набирают. */
    fun press(key: PadKey) {
        val field = typing ?: return
        val number = padNumber(field) ?: return
        field.type(field.text.press(key, number))
        noteProgress()
    }

    /**
     * Число, которое камера распознала, но не разложила по полям, — тапом по нему
     * в поле, которое набирают. Приложение не угадывает, куда его поставить, а
     * показывает всё, что увидело, и отдаёт решение человеку.
     */
    fun put(value: String) {
        val field = typing?.takeIf { padNumber(it) != null } ?: return
        field.type(value)
        noteProgress()
    }

    /** Долгое «стереть»: поле пустеет, и камера снятое значение больше не предлагает. */
    fun clearTyping() {
        typing?.takeIf { padNumber(it) != null }?.clear()
    }

    /** Чем набирается поле. `null` — название: его набирают клавиатурой. */
    fun padNumber(field: ScanField): PadNumber? = when (field) {
        kcal -> PadNumber.KCAL
        barcode -> PadNumber.CODE
        prot, fat, carb -> PadNumber.GRAMS
        else -> null
    }

    /** Число с этикетки, которого ещё нет. */
    fun isMissing(field: ScanField): Boolean =
        if (field === kcal) field.text.toIntOrNull() == null else field.text.toCentigrams() == null

    private fun firstMissingNumber(): ScanField? = numberFields.firstOrNull(::isMissing)

    private fun noteProgress() {
        val filled = filledFields
        if (filled != lastFilled) {
            lastFilled = filled
            calmSince = clock()
        }
    }

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
        if (next.available) seen++ else withoutCamera()
        blurSince = if (next.sharp) null else blurSince ?: clock()

        val fields = next.fields.toMap()
        votes = fields

        // В поля уходит только набранное голосами, и берётся оно у самого
        // голосования, а не из черновика кадра. Черновик, пока четвёрка
        // не сошлась целиком, показывает последний кадр как есть — и когда
        // человек уже отвёл камеру на полку, это ценник «2.49 5.53», разобранный
        // арифметикой. Поле белков при этом всё ещё считалось набранным:
        // три кадра с этикетки из окна не ушли, — и в него уезжало 5,5 с ценника
        // вместо нуля, за который кадры голосовали.
        offer(kcal, fields["ккал"]?.settledValue?.toString())
        offer(prot, fields["Б"]?.settledValue.centigramsToField())
        offer(fat, fields["Ж"]?.settledValue.centigramsToField())
        offer(carb, fields["У"]?.settledValue.centigramsToField())

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
        if (!name.filled) offer(name, next.name)
        noteProgress()
    }

    /**
     * В поле, которое сейчас набирают, камера не пишет вовсе. Камера прочла «5,5»,
     * человек стёр — и следующий же кадр вписывал «5,5» обратно: стёртое поле
     * снова считалось свободным.
     */
    private fun offer(field: ScanField, value: String?) {
        if (field !== typing) field.offer(value)
    }

    fun onBarcode(code: String) {
        offer(barcode, code)
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
            // Пустой макрос здесь — ноль, но только ради проверки на лету:
            // «950 ккал» или «120 г белка» должны краснеть сразу, не дожидаясь
            // остальных чисел. Сохранить с пустыми не даёт [canSave].
            Nutriments(
                kcal100 = it,
                prot100 = prot.text.toCentigrams() ?: 0,
                fat100 = fat.text.toCentigrams() ?: 0,
                carb100 = carb.text.toCentigrams() ?: 0,
            )
        }

    val check get() = nutriments?.let(NutrimentValidator::check)

    /**
     * Числа с этикетки, которых ещё нет: камера их не прочла, человек не вписал.
     *
     * Пустое поле — не ноль. Раньше продукт сохранялся с одними калориями, а
     * недочитанные белки, жиры и углеводы молча становились нулями и потом врали
     * в дневнике. Ноль, вписанный руками, — ответ: у масла белков и правда нет.
     */
    val missing: List<String>
        get() = buildList {
            if (kcal.text.toIntOrNull() == null) add("калории")
            if (prot.text.toCentigrams() == null) add("белки")
            if (fat.text.toCentigrams() == null) add("жиры")
            if (carb.text.toCentigrams() == null) add("углеводы")
        }

    /**
     * Замечание сверки по Этуотеру — когда есть что сверять.
     *
     * Пока числа вписаны не все, «выходит 0 ккал, а указано 121» — не замечание,
     * а упрёк человеку, который ещё не дописал.
     */
    val warning: String? get() = check?.warning?.takeIf { missing.isEmpty() }

    /** Код уходит в сохранение только пройдя контрольную цифру. */
    val gtin: String? get() = BarcodeValidator.normalize(barcode.text)

    /** Название нельзя ни вычислить, ни проверить, числа — только прочесть или вписать. */
    val canSave: Boolean get() = name.text.isNotBlank() && missing.isEmpty() && check?.valid == true

    /**
     * Сколько из пяти полей заполнено: название и четыре числа с этикетки.
     *
     * Штрихкод не считается: он необязателен, а после промаха скана заполнен
     * с самого начала — засчитай его, и пустая карточка считалась бы начатой.
     */
    val filledFields: Int
        get() = listOf(kcal, prot, fat, carb, name).count { it.filled }

    /** Код, с которым карточка открылась: он был до неё и потерей не считается. */
    private val initialBarcode = initialGtin.orEmpty()

    /**
     * Есть ли что терять, если закрыть карточку.
     *
     * Закрыть — одно касание «назад», а заполнить — минута с пачкой в руке:
     * камера набирала голоса кадр за кадром, человек дописывал то, что она
     * не прочла. Пока всё это есть, закрытие сначала спрашивает.
     */
    val hasDraft: Boolean
        get() = filledFields > 0 || (barcode.filled && barcode.text != initialBarcode)

    /** Сколько полей заполнила камера, а не человек, — для отклика ладони. */
    val cameraFilled: Int
        get() = listOf(kcal, prot, fat, carb, name, barcode).count { it.filled && !it.manual }

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
        /** Сколько ждать нового числа с камеры, прежде чем просить вписать руками. */
        const val STALL_MS = 20_000L

        /** Сколько терпеть мыло подряд: дольше — камера не наведётся сама. */
        const val BLUR_MS = 6_000L
    }
}

/** Часы карточки: с нуля в момент, когда она открылась. */
private fun monotonicClock(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
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
            state.fat.text, state.carb.text, state.barcode.text,
        )
    },
    restore = { saved ->
        ScanState(saved.getOrNull(5)?.takeIf { it.isNotEmpty() }).apply {
            // Восстановленное считается набранным человеком: после поворота
            // камера начнёт читать заново, и затирать пережившее поворот нельзя.
            name.type(saved[0])
            kcal.type(saved[1])
            prot.type(saved[2])
            fat.type(saved[3])
            carb.type(saved[4])
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
