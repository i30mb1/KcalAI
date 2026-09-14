package n7.kcalai.feature.scanner

import kotlin.math.abs
import kotlin.math.roundToInt
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductDraft
import n7.kcalai.repositories.LabelReading
import n7.kcalai.repositories.LabelTrace
import n7.kcalai.repositories.NutrimentValidator

/**
 * Согласие нескольких кадров вместо доверия одному.
 *
 * Распознаватель ошибается не систематически, а случайно: тот же участок пачки
 * при чуть другом фокусе, наклоне и освещении читается иначе. На кукурузе «80/»
 * один кадр отдаёт как «388», следующий как «80». Разбор каждого кадра честен —
 * он видит ровно то, что ему дали, — но отдать в дневник первое же сошедшееся
 * значит отдать то, которое ошиблось удачно: сошлось, а неверно.
 *
 * Три вещи, и каждая нужна.
 *
 * **Голосуют поля, а не кадры целиком.** Блик, севший на строку жиров, не портит
 * калории, снятые тем же кадром; требуя совпадения всего набора, пришлось бы
 * выбрасывать кадр из-за одного числа и ждать в разы дольше.
 *
 * **Голоса считаются с допуском.** Одно и то же число читается то как «87,6»,
 * то как «87», и точное сравнение раскололо бы голоса пополам ровно там, где
 * согласие на самом деле есть. Близкие значения собираются в кучу и голосуют
 * сообща, а победителем становится самое частое в ней.
 *
 * **Набранное сверяется по Этуотеру.** Четыре поля, каждое подтверждённое
 * тремя кадрами, могут всё равно не сходиться между собой — тогда согласия нет.
 * Досчитывать недостающий макрос арифметикой здесь не пробуем: это делает
 * разбор кадра, и такие значения в голосовании не участвуют.
 *
 * Голоса живут в скользящем окне, а не копятся вечно. Человек водит камерой
 * и может перевести её на другую пачку; голоса, набранные на прошлой, обязаны
 * состариться и уйти сами, иначе две этикетки смешаются в одну, которой нет.
 *
 * @param window сколько последних разборов участвует в голосовании
 * @param minVotes сколько раз значение должно повториться, чтобы ему поверили
 */
internal class LabelConsensus(
    private val window: Int = WINDOW,
    private val minVotes: Int = MIN_VOTES,
) {

    private val readings = ArrayDeque<LabelReading>()

    /**
     * Одно значение и то, насколько мы в нём уверены.
     *
     * @param votes сколько кадров его подтвердили; [needed] — сколько нужно
     */
    class Field(
        val value: Int?,
        val votes: Int,
        val needed: Int,
    ) {
        val settled: Boolean get() = value != null && votes >= needed

        /** Насколько набралось, 0..1 — для полоски прогресса. */
        val progress: Float
            get() = when {
                settled -> 1f
                needed <= 0 -> 0f
                else -> (votes.toFloat() / needed).coerceIn(0f, 1f)
            }
    }

    /**
     * Чем кончилось голосование.
     *
     * @param reading то, что показывать и отдавать: значения набраны голосованием,
     *        а строки, числа и названия — из последнего кадра, они нужны живыми
     */
    class Verdict(
        val reading: LabelReading,
        val kcal: Field,
        val prot: Field,
        val fat: Field,
        val carb: Field,
        val frames: Int,
        /**
         * Названия-кандидаты, отсортированные по числу подтвердивших кадров.
         *
         * Голосуют и они, хотя числами не являются. Причина та же, что у чисел,
         * но заметнее: список кандидатов от кадра к кадру перетасовывается
         * целиком, и чипсы под пальцем прыгали бы, пока человек в них целится.
         */
        val names: List<String> = emptyList(),
        /**
         * Название, которое подтвердило несколько кадров, — или `null`, пока такого нет.
         *
         * Отдельно от [names] по той же причине, по какой числа уходят в поля только
         * набранными: [names] это список для чипсов, где место есть и догадке,
         * а в поле должно попадать лишь то, за что кадры проголосовали.
         */
        val name: String? = null,
    ) {
        /** Все четыре значения набраны и сходятся между собой. */
        val settled: Boolean get() = reading.confident

        val fields: List<Pair<String, Field>>
            get() = listOf("ккал" to kcal, "Б" to prot, "Ж" to fat, "У" to carb)

        override fun toString(): String =
            fields.joinToString(" · ") { (name, field) -> "$name ${field.votes}/${field.needed}" }
    }

    fun add(reading: LabelReading): Verdict {
        readings.addLast(reading)
        while (readings.size > window) readings.removeFirst()

        // Голосуют только разборы по подписям. Подобранное арифметикой из чисел
        // всей этикетки от кадра к кадру складывается в разные тройки, и голосование
        // придало бы этой лотерее вид согласия — см. LabelParser.
        val voting = readings.filter { it.trace.route in LABELLED }

        var kcal = voting.vote(KCAL_TOLERANCE) { it.draft.kcal100 }
        var prot = voting.vote(MACRO_TOLERANCE) { it.draft.prot100 }
        var fat = voting.vote(MACRO_TOLERANCE) { it.draft.fat100 }
        var carb = voting.vote(MACRO_TOLERANCE) { it.draft.carb100 }

        val agreed = kcal.settled && prot.settled && fat.settled && carb.settled &&
            balances(kcal.value!!, prot.value!!, fat.value!!, carb.value!!)

        // Пока согласия нет, показывается последний разбор как есть: человек
        // должен видеть, что камера что-то читает, а не пустой экран.
        val latest = readings.last()
        val draft = if (agreed) {
            ProductDraft(
                name = latest.draft.name,
                kcal100 = kcal.value,
                prot100 = prot.value,
                fat100 = fat.value,
                carb100 = carb.value,
            )
        } else {
            latest.draft
        }

        val nameVotes = countNames()
        return Verdict(
            reading = latest.copy(draft = draft, confident = agreed),
            kcal = kcal,
            prot = prot,
            fat = fat,
            carb = carb,
            frames = readings.size,
            names = voteNames(nameVotes),
            name = confirmedName(nameVotes),
        )
    }

    /**
     * Названия по убыванию числа кадров, в которых они встретились.
     *
     * Повтор здесь значит больше, чем у чисел, и работает тоньше. Настоящее слово
     * читается одинаково кадр за кадром — буквы на месте, шрифт печатный,
     * и «Творог» остаётся «Творогом». Домысел распознавателя, наоборот, каждый
     * раз новый: вензель логотипа при другом фокусе разбирается иначе, и
     * подтвердить сам себя такая строка не может в принципе. Достаточно двух
     * совпадений, чтобы отделить одно от другого.
     *
     * Отбор по голосам включается, только когда набралось хоть одно подтверждённое
     * название: на первых кадрах его ещё нет ни у кого, и показать лучшее
     * из непроверенного всё же лучше, чем пустое поле.
     *
     * Равные по голосам идут в том порядке, в каком их отдал разбор, — а он
     * взвешивает уверенность распознавателя и размер надписи на пачке.
     */
    private fun voteNames(votes: Map<String, Int>): List<String> {
        val best = votes.values.maxOrNull() ?: return emptyList()
        val confirmed = if (best >= NAME_MIN_VOTES) {
            votes.filterValues { it >= NAME_MIN_VOTES }
        } else {
            votes
        }
        return confirmed.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(NAME_LIMIT)
    }

    /**
     * Сколько кадров прочитали каждое название.
     *
     * Порядок вставки значим и сохраняется: внутри одного кадра разбор уже
     * отсортировал кандидатов по уверенности и размеру надписи, и при равных
     * голосах побеждает его порядок, а не случайный обход хеш-таблицы.
     */
    private fun countNames(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        readings.forEach { reading ->
            reading.names.forEach { name -> counts.merge(name, 1, Int::plus) }
        }
        return counts
    }

    /** Лучшее из подтверждённых. Ничего не набрало голосов — `null`, и поле остаётся пустым. */
    private fun confirmedName(votes: Map<String, Int>): String? =
        votes.entries.filter { it.value >= NAME_MIN_VOTES }.maxByOrNull { it.value }?.key

    /**
     * Победившее значение с допуском на разброс распознавания.
     *
     * Близкие числа собираются в кучу и голосуют вместе: «87» и «87,6» — это
     * одно и то же значение, прочитанное дважды, а не два разных. Точное
     * сравнение раскололо бы такие голоса пополам ровно там, где согласие есть.
     * Представителем кучи становится самое частое в ней число.
     *
     * Ничья между **кучами** — другое дело: два далёких числа, набравших поровну,
     * означают, что кадр читается двояко, и выбирать за человека тут нечего.
     */
    private fun List<LabelReading>.vote(
        tolerance: Double,
        field: (LabelReading) -> Int?,
    ): Field {
        val values = mapNotNull(field)
        if (values.isEmpty()) return Field(value = null, votes = 0, needed = minVotes)

        val counts = values.groupingBy { it }.eachCount()
        val clusters = cluster(counts.keys.sorted(), tolerance)

        val scored = clusters.map { members -> members to members.sumOf { counts.getValue(it) } }
        val best = scored.maxBy { it.second }
        if (scored.count { it.second == best.second } > 1) {
            return Field(value = null, votes = best.second, needed = minVotes)
        }

        val representative = best.first.maxBy { counts.getValue(it) }
        return Field(value = representative, votes = best.second, needed = minVotes)
    }

    /** Соседние по величине значения, расходящиеся меньше допуска, — одно и то же число. */
    private fun cluster(sorted: List<Int>, tolerance: Double): List<List<Int>> {
        val clusters = mutableListOf<MutableList<Int>>()
        for (value in sorted) {
            val last = clusters.lastOrNull()
            val previous = last?.last()
            val allowed = previous?.let { abs(it) * tolerance + 1 } ?: 0.0
            if (last != null && previous != null && value - previous <= allowed) {
                last += value
            } else {
                clusters += mutableListOf(value)
            }
        }
        return clusters
    }

    /** Та же сверка по Этуотеру, что у формы: набранное голосами обязано сходиться. */
    private fun balances(kcal: Int, prot: Int, fat: Int, carb: Int): Boolean {
        if (kcal <= BALANCE_MIN_KCAL) return true
        val fromMacros = NutrimentValidator.atwaterKcal(
            Nutriments(kcal100 = kcal, prot100 = prot, fat100 = fat, carb100 = carb)
        )
        return abs(kcal - fromMacros) <= kcal * NutrimentValidator.BALANCE_TOLERANCE
    }

    private companion object {
        /**
         * Восемь кадров.
         *
         * Кадр с распознаванием идёт около шести десятых секунды — это замер
         * на живой съёмке, а не оценка, — так что восемь кадров это пять секунд
         * съёмки. Больше держать незачем: за это время человек успевает перевести
         * камеру, и хвост окна начинает говорить о другой пачке. Меньше — не хватит
         * запаса, когда пара кадров подряд смазалась: трём голосам неоткуда взяться.
         */
        const val WINDOW = 8

        /**
         * Три совпадения.
         *
         * Два — это одна ошибка, повторённая дважды подряд, что у распознавателя
         * бывает сплошь: соседние кадры почти одинаковы. Три уже требуют, чтобы
         * ошибка пережила смену фокуса. Больше — заметная задержка на ровном месте.
         */
        const val MIN_VOTES = 3

        /**
         * Допуск на разброс. Доля, а не число: у калорий это единицы, у макросов —
         * сотые грамма, и одна константа была бы верна ровно для одного из них.
         */
        const val KCAL_TOLERANCE = 0.02
        const val MACRO_TOLERANCE = 0.02

        const val BALANCE_MIN_KCAL = 20

        /** Сколько названий показывать. Больше — уже не выбор, а список. */
        const val NAME_LIMIT = 4

        /**
         * Сколько кадров должны прочитать строку одинаково, чтобы она считалась словом.
         *
         * Двух хватает, в отличие от чисел, где нужно три. Число распознаётся
         * из нескольких глифов и повторить ошибку может; строка из десятка букв,
         * совпавшая дважды посимвольно, случайностью уже не бывает.
         */
        const val NAME_MIN_VOTES = 2

        /** Разборы, которым голосование доверяет: каждое число взято у своей подписи. */
        val LABELLED = setOf(LabelTrace.Route.LABELS, LabelTrace.Route.LABELS_PARTIAL)
    }
}
