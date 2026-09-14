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
    /** Чем разбор руководствовался. Нужен только диагностике — см. [LabelTrace]. */
    val trace: LabelTrace = LabelTrace.EMPTY,
) {
    companion object {
        val EMPTY = LabelReading(ProductDraft.EMPTY, emptyList(), confident = false)
    }
}

/**
 * Что разбор увидел и как принял решение.
 *
 * На результат не влияет ничем и существует ради одного: **разбор этикетки
 * иначе непрозрачен.** Человек видит четыре числа в форме и не знает, взялись
 * они с подписей или их подобрала арифметика; разработчик, глядя на промах,
 * не знает, что именно не сработало — распознавание, привязка или перебор.
 * Тестовый режим съёмки показывает это всё на экране, а [n7.kcalai.model.TextLine]
 * распознанных строк уходит в лог.
 */
data class LabelTrace(
    /** Строки ровно в том виде, в каком их отдал распознаватель. */
    val lines: List<String> = emptyList(),
    val route: Route = Route.NOTHING,
    /** Подписи, прочитанные вместе со своим числом. */
    val readLabels: List<String> = emptyList(),
    /** Подписи, которых на этикетке нет, — их значения приняты за ноль. */
    val zeroedLabels: List<String> = emptyList(),
    /** Значения, не прочитанные, а досчитанные по Этуотеру из остальных трёх. */
    val derivedLabels: List<String> = emptyList(),
    /** Таблица пищевой ценности опознана: есть калории и хотя бы одна подпись макроса. */
    val tableFound: Boolean = false,
) {
    /** Откуда взялись числа. Порядок — от самого достоверного к самому слабому. */
    enum class Route(val title: String) {
        LABELS("по подписям"),
        LABELS_PARTIAL("по подписям, таблица прочитана не вся"),
        MIXED("подписи плюс арифметика"),
        NUMBERS("арифметикой по числам"),
        NOTHING("ничего не прочитано"),
    }

    companion object {
        val EMPTY = LabelTrace()
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
        val texts = lines.map { it.text }
        val numeric = parse(texts)

        fun trace(route: LabelTrace.Route, derived: List<String> = emptyList()) = LabelTrace(
            lines = texts,
            route = route,
            readLabels = anchored.readLabels,
            zeroedLabels = anchored.zeroedLabels,
            derivedLabels = derived,
            tableFound = anchored.tableFound,
        )

        // Таблица опознана — дальше работаем только с подписанным. Арифметику
        // по всем числам этикетки сюда пускать нельзя: она подберёт недостающее
        // из массы нетто и даты и подставит молча.
        if (anchored.tableFound) {
            val filled = complete(anchored)
            return LabelReading(
                draft = ProductDraft(
                    name = name,
                    kcal100 = filled.kcal100,
                    prot100 = filled.prot100,
                    fat100 = filled.fat100,
                    carb100 = filled.carb100,
                ),
                numbers = numeric.numbers,
                // Не сошлось — значит, одно из чисел распознано неверно, а какое,
                // отсюда не видно. Закрывать съёмку и подставлять это в дневник
                // нельзя: молча неверные калории хуже, чем честно незаполненная
                // форма, где числа с этикетки лежат чипсами под рукой.
                confident = filled.isComplete,
                names = names,
                trace = trace(
                    route = if (filled.isComplete) {
                        LabelTrace.Route.LABELS
                    } else {
                        LabelTrace.Route.LABELS_PARTIAL
                    },
                    derived = filled.derived,
                ),
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
        val route = when {
            anchored.readLabels.isNotEmpty() -> LabelTrace.Route.MIXED
            merged.kcal100 != null -> LabelTrace.Route.NUMBERS
            else -> LabelTrace.Route.NOTHING
        }
        return numeric.copy(
            draft = merged,
            // Арифметика заполняет форму, но закрывать съёмку сама не вправе,
            // и это не осторожность, а арифметика же. На этикетке два десятка
            // чисел; сочетаний из них по три с шестью перестановками — тысячи,
            // и найти среди них тройку, сходящуюся с калориями в пределах
            // допуска, можно почти всегда. Такое совпадение — не прочтение,
            // а подгонка: на майонезе она собирает «белки 1 г, углеводы 0,8 г»
            // из чисел таблицы на порцию, сходится с точностью до половины
            // процента и врёт по обоим числам. Отличить подгонку от чтения
            // изнутри нельзя, поэтому её показывают человеку, а не подставляют
            // молча. Подписанное — другое дело: там каждое число взято у своей
            // подписи, и подгонять было нечего.
            confident = false,
            names = names,
            trace = trace(route),
        )
    }

    /**
     * Строки, которые могут оказаться названием продукта.
     *
     * Отбор грубый и не претендует на большее: цифры, единицы измерения, служебные
     * надписи и всё короткое — не название.
     *
     * **Крупнее — не значит вернее, и это здесь главное.** Самая крупная надпись
     * на пачке это логотип, а логотип набран рисованным шрифтом: вензеля, тени,
     * растянутые буквы. Распознающая модель обучена на печатном тексте и на таком
     * шрифте не отказывается, а честно выдаёт свою лучшую догадку — «Ннаьуи»
     * вместо «Danone». Сортировка по одной высоте ставила эту догадку первой,
     * и в поле попадал набор символов.
     *
     * Поэтому голосуют три вещи сразу: уверенность распознавателя (на вензелях
     * она проседает), доля высоты от самой крупной строки в кадре и форма самого
     * слова — см. [readsLikeWords]. Состав, набранный мелко и чётко, обгоняет
     * логотип, прочитанный крупно и мимо.
     *
     * Кандидаты приводятся к виду, который человек согласится увидеть в дневнике:
     * с пачки они приходят капсом, в кавычках-ёлочках и с хвостами распознавания,
     * а в ленте потом стоят рядом с «Гречка» и «Кофе с молоком». Строка «ТВОРОГ
     * 5% "ПРОСТОКВАШИНО"» — это то же название, но записанное так, как его никто
     * не пишет руками.
     *
     * Угадывать молча тут всё равно нельзя: человек смотрит на пачку, и дать ему
     * выбор честнее, чем настаивать на догадке.
     */
    fun nameCandidates(lines: List<TextLine>, limit: Int = NAME_LIMIT): List<String> {
        val tallest = lines.maxOfOrNull { it.height }?.takeIf { it > 0f } ?: return emptyList()
        return lines.asSequence()
            .filterNot { isServiceText(it.text) }
            // Строка, в которой не уверен сам распознаватель, не может быть
            // названием: ошибку в подписи «Белки» ловит арифметика, а ошибку
            // в названии — никто, она уедет в дневник как есть.
            .filter { it.confidence >= NAME_MIN_CONFIDENCE }
            .mapNotNull { line ->
                humanizeName(line.text)?.let { it to line.confidence * (line.height / tallest) }
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(limit)
            .toList()
    }

    /**
     * Распознанная строка в человеческое название — или `null`, если это не оно.
     *
     * Порог по доле букв отсекает мусор распознавания: у строки вроде «5%:1|2,3»
     * букв меньше трети, и названием она не является ни при каком раскладе.
     */
    fun humanizeName(raw: String): String? {
        val cleaned = raw
            .replace(NAME_NOISE, " ")
            .replace(SPACES, " ")
            .trim()
            .trim('"', '«', '»', '\'', '-', '·', ',', '.', ':', ';')
            .trim()

        if (cleaned.length < NAME_MIN_LENGTH) return null

        val letters = cleaned.count(Char::isLetter)
        if (letters < cleaned.length * NAME_MIN_LETTER_SHARE) return null
        if (letters < cleaned.count(Char::isDigit)) return null
        if (!readsLikeWords(cleaned)) return null

        // Капс с пачки — не выделение, а типографика упаковки. Переносить её
        // в ленту незачем: там «ТВОРОГ» кричит рядом с «Гречка».
        val upper = cleaned.count { it.isLetter() && it.isUpperCase() }
        val normalized = if (upper >= letters * CAPS_SHARE && letters > SHORT_ABBREVIATION) {
            cleaned.lowercase(RU)
        } else {
            cleaned
        }

        return normalized.replaceFirstChar { it.titlecase(RU) }
    }

    /**
     * Читается ли строка как слова, а не как догадка распознавателя.
     *
     * Доли букв недостаточно, и это тонкое место: «Ннаьуи» состоит из букв
     * на все сто процентов и любую проверку по символам проходит. Отличается
     * оно не составом, а **строением**: настоящее слово подчиняется фонетике
     * языка, на котором напечатано, а собранное по глифам — нет.
     *
     * Трёх признаков хватает, и каждый ловит свой вид мусора:
     *
     * 1. **Два алфавита в одном слове.** Кириллическая «А» и латинская «l» рядом
     *    не встречаются ни в одном настоящем слове — так распознаватель признаётся,
     *    что выбирал глифы поодиночке. Целиком латинское слово при этом законно:
     *    «Alpen Gold» напечатан именно так.
     * 2. **Слово без гласных.** Их нет ни в русском, ни в английском.
     * 3. **Пять согласных подряд или буква трижды кряду.** «Вздрогнув» даёт
     *    четыре, а пять — уже не язык.
     *
     * Судится не строка целиком, а каждое слово, и решает перевес букв: на пачке
     * рядом с названием стоят и «БЗМЖ», и обрывки состава. «Творог БЗМЖ» — это
     * название, где одно слово из двух аббревиатура, и терять его незачем.
     */
    private fun readsLikeWords(cleaned: String): Boolean {
        var wordly = 0
        var garbled = 0
        for (word in cleaned.split(SPACES)) {
            val letters = word.filter(Char::isLetter)
            // Предлоги и проценты формой ничего не доказывают ни в ту, ни в другую
            // сторону: в «с» нет согласных подряд, но нет и слова.
            if (letters.length < WORD_MIN_LENGTH) continue
            if (letters.readsLikeWord()) wordly += letters.length else garbled += letters.length
        }
        return wordly > garbled
    }

    /** @receiver только буквы слова, без знаков и цифр. */
    private fun CharSequence.readsLikeWord(): Boolean {
        val cyrillic = count { it in CYRILLIC }
        if (cyrillic != 0 && cyrillic != length) return false
        val lower = toString().lowercase(RU)
        if (lower.none { it in VOWELS }) return false
        if (CONSONANT_RUN.containsMatchIn(lower)) return false
        if (TRIPLED_LETTER.containsMatchIn(lower)) return false
        return true
    }

    private val CYRILLIC = 'Ѐ'..'ӿ'

    private const val VOWELS = "аеёиоуыэюяaeiouy"

    /** Согласные обоих алфавитов; твёрдый и мягкий знаки идут с ними — гласными они не являются. */
    private val CONSONANT_RUN = Regex("""[бвгджзйклмнпрстфхцчшщъьbcdfghjklmnpqrstvwxz]{5,}""")

    private val TRIPLED_LETTER = Regex("""(\p{L})\1\1""")

    /** Короче этого слово формой ничего не доказывает — см. [readsLikeWords]. */
    private const val WORD_MIN_LENGTH = 3

    /**
     * Ниже этой уверенности строка в названия не идёт.
     *
     * Порог низкий намеренно: он отсекает не сомнительное, а то, где модель сама
     * себе не верит, — вензеля логотипа и текст под бликом. Настоящее название,
     * даже прочитанное с опечаткой в букве, держится заметно выше.
     */
    private const val NAME_MIN_CONFIDENCE = 0.6f

    /**
     * Служебная ли это надпись.
     *
     * Проверяется не сама строка, а её вид после [foldHomoglyphs] — и вот почему
     * это обязательно. Латинская `c` и кириллическая `с` неразличимы на глаз
     * и почти неразличимы для модели: она выбирает между ними по контексту
     * и на коротком слове ошибается. «Масса» приходит как `Macca` — те же буквы
     * на экране, но другие кодовые точки, и список служебных слов, написанный
     * кириллицей, проходит мимо. Именно так «Масса» и попадала в название.
     */
    private fun isServiceText(raw: String): Boolean =
        NOT_A_NAME.containsMatchIn(foldHomoglyphs(raw))

    /**
     * Латинские двойники кириллических букв — к общему виду.
     *
     * Только те пары, что совпадают начертанием: `а с е о р х у` и заглавные
     * `А В Е К М Н О Р С Т У Х`. Строка сперва приводится к нижнему регистру,
     * поэтому таблица одна на оба случая.
     *
     * Обратно — из кириллицы в латиницу — не нужно: служебные надписи на пачке
     * русские, и ловим мы именно их.
     */
    private fun foldHomoglyphs(raw: String): String =
        raw.lowercase(RU).map { HOMOGLYPHS[it] ?: it }.joinToString("")

    private val HOMOGLYPHS = mapOf(
        'a' to 'а', 'b' to 'в', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'k' to 'к',
        'm' to 'м', 'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у',
    )

    /**
     * Слова, которых в названии продукта не бывает.
     *
     * Список корней, а не целых слов: «хранить», «хранения» и «хранить при
     * температуре» — это одно и то же «хран». Падежи русского языка иначе
     * пришлось бы перечислять вручную, и один пропущенный давал бы «Массу»
     * в поле названия.
     *
     * **Чего здесь намеренно нет.** «Сахар», «Соль», «Мука», «Масло» — это
     * служебные слова в составе и одновременно настоящие названия продуктов:
     * пачка сахара называется «Сахар». Отсеивать их значило бы ломать простой
     * случай ради сложного. Их отводит другое — состав напечатан мелко,
     * а название крупно, и отбор взвешивает размер надписи.
     *
     * Строки — фрагменты регулярного выражения, а не литералы: `объ[её]м`
     * закрывает обе орфографии сразу.
     */
    private val NOT_A_NAME_ROOTS = listOf(
        // Таблица пищевой ценности.
        "ккал", "кдж", "kcal", "белк", "белок", "жир", "углевод", "энергет",
        "пищев", "ценност", "клетчатк", "насыщенн", "витамин",
        // Сколько его тут.
        "масс", "нетто", "брутто", "объ[её]м", "порци", "суточн", "потребност",
        // Состав, хранение, обращение.
        "состав", "содерж", "хран", "годн", "срок", "температур", "услови",
        "холодильн", "вскрыт", "употреб", "разогре", "аллерг", "приготовл",
        // Кто сделал, где и по какому документу.
        "изготов", "производ", "упаков", "фасов", "импорт", "постав", "адрес",
        "гост", "ту\\s", "стандарт", "сертифик", "парти", "реглам",
        "обществ", "\\bооо\\b", "\\bоао\\b", "\\bзао\\b", "\\bип\\b",
        // Как с ними связаться.
        "www", "\\.ru", "\\.com", "\\.рф", "тел\\.", "штрих", "потребител",
    )

    private val NOT_A_NAME = Regex(NOT_A_NAME_ROOTS.joinToString("|"), RegexOption.IGNORE_CASE)

    /** Что распознаватель дописывает от себя: одиночные скобки, палки, решётки. */
    private val NAME_NOISE = Regex("""[|\\/_~^*#<>\[\]{}()]+""")
    private val SPACES = Regex("""\s+""")

    private val RU = java.util.Locale("ru")

    /** Ниже этой доли букв строка — мусор распознавания, а не слово. */
    private const val NAME_MIN_LETTER_SHARE = 0.55

    /** С какой доли заглавных строка считается набранной капсом. */
    private const val CAPS_SHARE = 0.8

    /** «БЗМЖ» и «ГОСТ» капсом и остаются: короткое слово капсом — аббревиатура. */
    private const val SHORT_ABBREVIATION = 4

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
        val nothing = LabelTrace(lines = lines, route = LabelTrace.Route.NOTHING)
        if (numbers.isEmpty()) return LabelReading.EMPTY.copy(trace = nothing)

        val values = numbers.mapNotNull { it.toDoubleOrNull() }
        val shown = numbers.map { it.replace('.', ',') }

        val candidates = energyCandidates(values)
        if (candidates.isEmpty()) {
            return LabelReading(ProductDraft.EMPTY, shown, confident = false, trace = nothing)
        }

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
                trace = LabelTrace(lines = lines, route = LabelTrace.Route.NUMBERS),
            )
        }

        return LabelReading(
            draft = ProductDraft(kcal100 = candidates.first()),
            numbers = shown,
            confident = false,
            trace = LabelTrace(lines = lines, route = LabelTrace.Route.NUMBERS),
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

    /** Четыре значения после дозаполнения плюс список того, что не прочитано, а вычислено. */
    private class Filled(
        val kcal100: Int?,
        val prot100: Int?,
        val fat100: Int?,
        val carb100: Int?,
        val derived: List<String> = emptyList(),
    ) {
        val isComplete: Boolean
            get() = kcal100 != null && prot100 != null && fat100 != null && carb100 != null
    }

    /**
     * Достраивает набор по формуле Этуотера.
     *
     * Четыре величины связаны одним уравнением: калории равны четырём на белки,
     * девяти на жиры и четырём на углеводы. Три известных определяют четвёртое
     * однозначно — тут нечего подбирать, ответ ровно один. Строка, которую
     * закрыл блик, перестаёт держать всю съёмку.
     *
     * Уравнение работает в обе стороны, и обе нужны, но доверия они требуют
     * разного.
     *
     * **Недостающий макрос** считается, только когда калории подтверждены сами
     * по себе — килокалории и килоджоули прочитаны оба и сошлись. Считать макрос
     * от числа, в котором мы не уверены, значит размножить ошибку.
     *
     * **Недостающие калории** — наоборот, когда прочитать их не вышло, а все три
     * макроса взяты у своих подписей. Именно так на кукурузе: ячейка «80/340»
     * распозналась одним куском как «388», настоящих чисел в кадре нет вовсе,
     * зато Б, Ж и У прочитаны верно и дают восемьдесят одну — то, что напечатано,
     * с точностью до округления. Три независимых прочтения против одного
     * заведомо неверного.
     *
     * Принятые за ноль макросы такого права не дают: ноль там не прочитан,
     * а выведен из отсутствия подписи, и строить на нём ещё одно значение
     * значит городить догадку на догадке.
     */
    private fun complete(anchored: LabelAnchors.Anchored): Filled {
        val prot = anchored.prot100
        val fat = anchored.fat100
        val carb = anchored.carb100

        if (prot != null && fat != null && carb != null) {
            val fromMacros = NutrimentValidator.atwaterKcal(
                Nutriments(kcal100 = 0, prot100 = prot, fat100 = fat, carb100 = carb)
            )
            // Калории прочитаны дважды, килокалориями и килоджоулями. Побеждает
            // то прочтение, которое макросы признают; не признан ни один —
            // значит, оба прочитаны неверно, и считать надо самим.
            anchored.kcalCandidates.firstOrNull { it.balancesWith(fromMacros) }
                ?.let { return Filled(it, prot, fat, carb) }

            if (anchored.zeroedLabels.isEmpty()) {
                val derived = fromMacros.roundToInt()
                if (derived in KCAL_MIN..NutrimentValidator.KCAL_MAX) {
                    return Filled(derived, prot, fat, carb, derived = listOf("ккал"))
                }
            }
            return Filled(anchored.kcal100, prot, fat, carb)
        }

        val kcal = anchored.kcal100
        val missing = listOf(prot, fat, carb).count { it == null }
        if (kcal == null || missing != 1 || !anchored.kcalCorroborated) {
            return Filled(kcal, prot, fat, carb)
        }

        // Килокалории на сто грамм — в сотых, как и макросы: считать надо
        // в одних единицах, иначе жир выйдет ровно в сто раз не тот.
        val residual = kcal * 100 -
            (prot ?: 0) * PROT_KCAL_PER_G -
            (fat ?: 0) * FAT_KCAL_PER_G -
            (carb ?: 0) * CARB_KCAL_PER_G

        val perGram = when {
            prot == null -> PROT_KCAL_PER_G
            fat == null -> FAT_KCAL_PER_G
            else -> CARB_KCAL_PER_G
        }
        // Клетчатка и многоатомные спирты дают на настоящих этикетках законный
        // разбег, и на продукте, где он велик, остаток выйдет отрицательным
        // или несуразным. Такой ответ не выводится, а отбрасывается.
        val value = (residual.toDouble() / perGram).roundToInt()
        if (value < -DERIVE_SLACK_CG || value > MACRO_SUM_MAX + DERIVE_SLACK_CG) {
            return Filled(kcal, prot, fat, carb)
        }

        val bounded = value.coerceIn(0, MACRO_SUM_MAX)
        return when {
            prot == null -> Filled(kcal, bounded, fat, carb, derived = listOf("Б"))
            fat == null -> Filled(kcal, prot, bounded, carb, derived = listOf("Ж"))
            else -> Filled(kcal, prot, fat, bounded, derived = listOf("У"))
        }
    }

    private const val PROT_KCAL_PER_G = 4
    private const val FAT_KCAL_PER_G = 9
    private const val CARB_KCAL_PER_G = 4

    /** Полграмма запаса: клетчатка и округление на пачке дают остаток чуть мимо нуля. */
    private const val DERIVE_SLACK_CG = 50

    /**
     * Сходятся ли эти калории с тем, что дают макросы.
     *
     * Допуск — тот же, которым форма помечает подозрительным ручной ввод:
     * клетчатка и многоатомные спирты дают законный разбег, и придираться
     * к нему незачем. Совсем малые калории не проверяются вовсе — у зелени
     * и напитков макросы околонулевые, и относительная погрешность там
     * не значит ничего.
     */
    private fun Int.balancesWith(fromMacros: Double): Boolean =
        this <= BALANCE_MIN_KCAL ||
            abs(this - fromMacros) <= this * NutrimentValidator.BALANCE_TOLERANCE

    /** Ниже этого порога сверка энергобаланса бессмысленна. */
    private const val BALANCE_MIN_KCAL = 20

    private fun Macros.toNutriments(kcal: Int) =
        Nutriments(kcal100 = kcal, prot100 = prot100, fat100 = fat100, carb100 = carb100)

    /** Граммы с этикетки в сотые грамма модели. */
    private fun Double.toCentigrams(): Int = (this * 100).roundToInt()
}
