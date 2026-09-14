package n7.kcalai.repositories

import kotlin.math.abs
import kotlin.math.roundToInt
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductDraft
import n7.kcalai.model.TextLine

/**
 * Что удалось прочитать с таблицы пищевой ценности.
 *
 * @param numbers все распознанные числа в порядке чтения. Нужны, когда разбор
 *        не сошёлся: форма показывает их чипсами, и человек назначает сам.
 *        Четыре тапа — это всё равно несопоставимо быстрее, чем набрать «12,4»
 *        на мобильной клавиатуре четыре раза.
 * @param confident найдены калории и все три макроса, и они сходятся между собой
 */
data class LabelReading(
    val draft: ProductDraft,
    val numbers: List<String>,
    val confident: Boolean,
    /**
     * Строки, которые могут оказаться названием продукта, от самой вероятной.
     *
     * Первая подставляется в поле, остальные форма показывает чипсами. Название —
     * единственное, что нельзя ни вычислить, ни проверить арифметикой, поэтому
     * тут только предложение, а решает человек.
     */
    val names: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = LabelReading(ProductDraft.EMPTY, emptyList(), confident = false)
    }
}

/**
 * Числа с этикетки -> КБЖУ, без единой прочитанной буквы.
 *
 * ML Kit не распознаёт кириллицу — ни «Белки», ни «Жиры» он не увидит никогда.
 * Зато цифры набраны латинскими глифами и читаются отлично. Поэтому задача здесь
 * стоит иначе, чем у обычного OCR-разбора: **дана горсть чисел без подписей,
 * надо понять, какое из них что.** Решается арифметикой.
 *
 * Две опоры, и обе не требуют текста:
 *
 * 1. **Энергия.** По ТР ТС 022/2011 её печатают дважды — в килокалориях
 *    и килоджоулях. Отношение этих чисел равно физической константе 4.184,
 *    и пара, которая в неё попадает, опознаётся практически безошибочно.
 *
 * 2. **Макросы.** Зная калории, остальные три числа расставляет формула Этуотера:
 *    перебираются сочетания и все шесть перестановок, выигрывает та, что сходится.
 *
 * **Чего арифметика не может в принципе.** У белка и углеводов один и тот же
 * коэффициент — 4 ккал на грамм, — поэтому поменять их местами энергия не заметит:
 * оба набора дают ровно одно число. Различить их формулой нельзя, и притворяться,
 * что можно, было бы хуже всего. Жир коэффициент 9 выдаёт надёжно, а белок
 * с углеводами назначаются по порядку чтения: на русских этикетках печатают
 * «Белки, Жиры, Углеводы» — БЖУ и есть этот порядок.
 *
 * Ошибиться здесь разбор может, и человек это увидит: перепутанные Б и У бросаются
 * в глаза при сверке с пачкой. Калории при этом останутся верными в любом случае —
 * а именно их и считает дневник.
 *
 * Чего разбор не даёт вовсе: названия продукта (кириллица) и веса порции
 * (подпись «г» не читается). Их вводит человек — одно поле вместо пяти.
 */
object LabelParser {

    /** Килоджоули в килокалории. Международная таблица, не округление. */
    private const val KJ_PER_KCAL = 4.184

    /**
     * Допуск на отношение кДж/ккал.
     *
     * Два процента, а не десять: обе цифры на этикетке посчитаны из одного и того же
     * значения и расходятся только на округление до целых — реально это доли процента.
     * Широкий допуск ловит ложные пары, и ловит обидно. Год «2026» из срока годности
     * и настоящие «506» кДж дают отношение 4.00, и при допуске в пять процентов
     * разбор принимает год за килоджоули, а килоджоули — за калории.
     */
    private const val KJ_RATIO_TOLERANCE = 0.02

    /**
     * Границы осмысленных калорий на 100 г.
     *
     * Верхняя — физический потолок чистого жира, тот же, что в [NutrimentValidator].
     * Нижняя отсекает мелочь вроде «2 г», случайно попавшую в кандидаты.
     */
    private const val KCAL_MIN = 10

    /** Макрос не может быть больше 100 г в 100 г. */
    private const val MACRO_MAX = 100.0

    /** То же в сотых грамма — для проверки суммы трёх макросов. */
    private const val MACRO_SUM_MAX = 100 * 100

    /**
     * Сколько чисел вообще имеет смысл перебирать.
     *
     * Сочетаний из n по 3 растёт кубически, а этикетка с двумя колонками, массой нетто
     * и сроком годности легко даёт полтора десятка чисел. На двадцати это 1140
     * сочетаний по 6 перестановок — всё ещё доли миллисекунды, а дальше расти незачем:
     * нужные числа стоят в начале таблицы, а не в конце упаковки.
     */
    private const val MAX_CANDIDATES = 20

    /**
     * Сколько кандидатов на калории проверяется макросами.
     *
     * Каждый стоит полного перебора макросов, а верные стоят в начале списка:
     * пара кДж/ккал почти всегда попадает первой. Шести хватает с запасом.
     */
    private const val MAX_ENERGY_CANDIDATES = 6

    /**
     * Число на этикетке: целое или дробное, разделитель любой.
     *
     * Пробел внутри числа не допускается намеренно: «450 г» это одно число и буква,
     * а не «450» и «г», склеенные в «450 г». А вот `12.4` и `12,4` — одно и то же,
     * и какой разделитель напечатает типография, предсказать нельзя.
     */
    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")

    /**
     * Разбирает строки вместе с их положением на снимке — основной путь.
     *
     * Сначала пробуем прочитать подписи: «Белки» и число на одной строке связаны
     * прямо, без догадок. Это снимает единственную принципиальную слабость
     * арифметики — она не различает белки и углеводы, у них одинаковый
     * коэффициент 4 ккал/г.
     *
     * Подписей не нашлось (латинский OCR, блик, нестандартная вёрстка) —
     * работает [parse] по числам, как раньше. Запасной путь обязан остаться
     * рабочим: моделей кириллицы на устройстве может не быть вовсе.
     */
    fun parseLines(lines: List<TextLine>): LabelReading {
        val anchored = LabelAnchors.read(lines)
        val names = nameCandidates(lines)
        val name = names.firstOrNull()
        val numeric = parse(lines.map { it.text })

        if (anchored.complete) {
            return LabelReading(
                draft = ProductDraft(
                    name = name,
                    kcal100 = anchored.kcal100,
                    prot100 = anchored.prot100,
                    fat100 = anchored.fat100,
                    carb100 = anchored.carb100,
                ),
                numbers = numeric.numbers,
                confident = true,
                names = names,
            )
        }

        // Таблица прочитана, но не вся. Арифметику сюда пускать нельзя: она
        // подберёт недостающее из посторонних чисел — массы нетто, даты, —
        // и подставит их молча. Подписанное достовернее подобранного, а чего
        // на этикетке нет, того нет.
        if (anchored.tableFound) {
            return LabelReading(
                draft = ProductDraft(
                    name = name,
                    kcal100 = anchored.kcal100,
                    prot100 = anchored.prot100,
                    fat100 = anchored.fat100,
                    carb100 = anchored.carb100,
                ),
                numbers = numeric.numbers,
                confident = false,
                names = names,
            )
        }

        // Привязка неполная. Подписанное число всё равно достовернее подобранного
        // перебором, поэтому берём его там, где оно есть, а остальное — у арифметики.
        val merged = numeric.draft.copy(
            name = name,
            kcal100 = anchored.kcal100 ?: numeric.draft.kcal100,
            prot100 = anchored.prot100 ?: numeric.draft.prot100,
            fat100 = anchored.fat100 ?: numeric.draft.fat100,
            carb100 = anchored.carb100 ?: numeric.draft.carb100,
        )
        return numeric.copy(
            draft = merged,
            confident = numeric.confident || merged.isComplete,
            names = names,
        )
    }

    /**
     * Строки, которые могут оказаться названием продукта.
     *
     * Отбор грубый и не претендует на большее: цифры, единицы измерения
     * и всё короткое — не название. Остальное сортируется по высоте букв,
     * потому что название на упаковке печатают крупнее состава.
     *
     * Первая строка подставляется в поле, остальные уходят в чипсы. Угадывать
     * молча тут нельзя — человек всё равно смотрит на пачку, и дать ему выбор
     * честнее, чем настаивать на догадке.
     */
    fun nameCandidates(lines: List<TextLine>, limit: Int = NAME_LIMIT): List<String> =
        lines.asSequence()
            .filter { it.text.trim().length >= NAME_MIN_LENGTH }
            .filter { line -> line.text.count(Char::isLetter) >= line.text.count(Char::isDigit) }
            .filterNot { NOT_A_NAME.containsMatchIn(it.text) }
            .sortedByDescending { it.height }
            .map { it.text.trim() }
            .distinct()
            .take(limit)
            .toList()

    /** Подписи таблицы и служебные надписи: названием продукта они не бывают. */
    private val NOT_A_NAME = Regex(
        """ккал|кдж|kcal|белк|жир|углевод|соль|состав|годн|хранен|изготов|масса|нетто""",
        RegexOption.IGNORE_CASE,
    )

    private const val NAME_MIN_LENGTH = 3
    private const val NAME_LIMIT = 5

    /**
     * Разбирает распознанный текст.
     *
     * Две колонки — «на 100 г» и «на порцию» — разводятся сами, без эвристик
     * про то, какая где стоит. Калориями становится энергия первой колонки,
     * а макросы второй с ней не сойдутся: они пересчитаны на другой вес
     * и промахиваются по Этуотеру во столько же раз. Выигрывает набор,
     * принадлежащий той же колонке, что и калории.
     *
     * @param lines строки в том порядке, в каком их вернул распознаватель, —
     *        сверху вниз и слева направо. Порядок значим: при равной невязке
     *        побеждает то, что стоит на этикетке выше.
     */
    fun parse(lines: List<String>): LabelReading {
        val numbers = lines.flatMap { line ->
            NUMBER.findAll(line).map { it.value.replace(',', '.') }
        }
        if (numbers.isEmpty()) return LabelReading.EMPTY

        val values = numbers.mapNotNull { it.toDoubleOrNull() }
        val shown = numbers.map { it.replace('.', ',') }

        val candidates = energyCandidates(values)
        if (candidates.isEmpty()) return LabelReading(ProductDraft.EMPTY, shown, confident = false)

        // Кандидат на калории не принимается на веру: его должны подтвердить макросы.
        // Это тот же приём, что разводит две колонки, — арбитром везде выступает
        // Этуотер, а не порядок и не размер числа.
        for (kcal in candidates) {
            val macros = findMacros(values, kcal) ?: continue
            return LabelReading(
                draft = ProductDraft(
                    kcal100 = kcal,
                    prot100 = macros.prot100,
                    fat100 = macros.fat100,
                    carb100 = macros.carb100,
                ),
                numbers = shown,
                confident = true,
            )
        }

        return LabelReading(
            draft = ProductDraft(kcal100 = candidates.first()),
            numbers = shown,
            confident = false,
        )
    }

    /**
     * Чем могут оказаться калории, от самого надёжного признака к самому слабому.
     *
     * 1. **Пара «кДж / ккал».** Отношение 4.184 — физическая константа, и другой
     *    пары величин с таким отношением на этикетке нет. Самый сильный признак.
     * 2. **Одинокие килоджоули.** Если пары нет, но есть число в диапазоне кДж,
     *    делим его на 4.184: часть упаковок печатает только килоджоули.
     * 3. **Просто крупное правдоподобное число.** Энергетическая ценность обычно
     *    самая большая цифра в таблице. Чистая догадка — идёт последней.
     *
     * Возвращается список, а не один ответ: выбрать из него — дело [findMacros],
     * который проверит каждого кандидата на сходимость.
     */
    private fun energyCandidates(values: List<Double>): List<Int> {
        val plausible = KCAL_MIN..NutrimentValidator.KCAL_MAX

        val fromPair = buildList {
            for (kj in values) {
                for (kcal in values) {
                    if (kcal <= 0) continue
                    val expected = kcal * KJ_PER_KCAL
                    if (abs(kj - expected) <= expected * KJ_RATIO_TOLERANCE) {
                        kcal.roundToInt().takeIf { it in plausible }?.let(::add)
                    }
                }
            }
        }

        val fromKj = values
            .filter { it > NutrimentValidator.KCAL_MAX }
            .map { (it / KJ_PER_KCAL).roundToInt() }
            .filter { it in plausible }

        // Дробные числа отсеиваются: энергетическую ценность печатают целой,
        // а «17,2» — это заведомо макрос.
        val loneNumbers = values
            .filter { it == it.roundToInt().toDouble() && it.roundToInt() in plausible }
            .map { it.roundToInt() }
            .sortedDescending()

        return (fromPair + fromKj + loneNumbers).distinct().take(MAX_ENERGY_CANDIDATES)
    }

    private class Macros(val prot100: Int, val fat100: Int, val carb100: Int)

    /**
     * Расставляет три числа по местам формулой Этуотера.
     *
     * Перебираются все сочетания кандидатов и все шесть перестановок каждого;
     * выигрывает та, что ближе всего к найденным калориям. Кандидатом считается
     * любое число от нуля до ста — то есть и калории тоже, если их меньше ста.
     * Исключать их отдельно не нужно: набор с калориями в роли жира разойдётся
     * с энергией на порядок и проиграет.
     *
     * Сравнение строгое (`<`), и это существенно, а не стилистика. Белок
     * и углеводы дают одинаковую невязку при любой перестановке — коэффициент
     * у обоих 4, — поэтому первым найденным остаётся то, что стоит на этикетке
     * выше. Русские упаковки печатают «Белки, Жиры, Углеводы», и порядок чтения
     * оказывается единственным, чем эти два числа вообще можно различить.
     */
    private fun findMacros(values: List<Double>, kcal: Int): Macros? {
        val candidates = values
            .filter { it in 0.0..MACRO_MAX }
            .take(MAX_CANDIDATES)
        if (candidates.size < 3) return null

        var best: Macros? = null
        var bestError = Double.MAX_VALUE

        for (i in candidates.indices) {
            for (j in candidates.indices) {
                if (j == i) continue
                for (k in candidates.indices) {
                    if (k == i || k == j) continue

                    val macros = Macros(
                        prot100 = candidates[i].toCentigrams(),
                        fat100 = candidates[j].toCentigrams(),
                        carb100 = candidates[k].toCentigrams(),
                    )
                    if (macros.prot100 + macros.fat100 + macros.carb100 > MACRO_SUM_MAX) continue

                    val error = abs(kcal - NutrimentValidator.atwaterKcal(macros.toNutriments(kcal)))
                    if (error < bestError) {
                        bestError = error
                        best = macros
                    }
                }
            }
        }

        // Тот же допуск, что у формы: набор, который валидатор тут же пометит
        // подозрительным, предлагать человеку незачем.
        return best?.takeIf { bestError <= kcal * NutrimentValidator.BALANCE_TOLERANCE }
    }

    private fun Macros.toNutriments(kcal: Int) =
        Nutriments(kcal100 = kcal, prot100 = prot100, fat100 = fat100, carb100 = carb100)

    /** Граммы с этикетки в сотые грамма модели. */
    private fun Double.toCentigrams(): Int = (this * 100).roundToInt()
}
