package n7.kcalai.resolver

import n7.kcalai.fooddb.normalizeForSearch

/** Как в сегменте было названо количество. */
sealed interface Quantity {

    /** Явный вес: «200 г», «0.5 кг», «250 мл». */
    data class Weight(val grams: Int) : Quantity

    /** Счёт в порционных единицах: «2 шт», «ложка», «полстакана». */
    data class Units(val count: Double, val unit: String) : Quantity

    /** Голое число без единицы: «курица 150», «2 яйца». Что это — решается по продукту. */
    data class Bare(val count: Double) : Quantity

    /** «немного», «много» — множитель к типичной порции. */
    data class Vague(val factor: Double) : Quantity

    /** Количество не названо вовсе. */
    data object None : Quantity
}

data class ParsedSegment(
    /** Исходный текст сегмента, как его написал человек. */
    val raw: String,
    /** То, что пойдёт в поиск: слова без чисел, единиц и «немного». */
    val name: String,
    val quantity: Quantity,
)

/**
 * Канонические имена порционных единиц.
 *
 * Набор обязан совпадать с `CANONICAL_UNITS` в `tools/builddb/build_seed.py`:
 * там синонимы («ломтик», «кружка») схлопываются в канон на сборке базы, здесь —
 * распознаются в тексте. Единица, известная одной стороне и неизвестная другой,
 * не ломает сборку, а молча подменяет названный вес типичной порцией.
 */
object Units {
    const val PIECE = "шт"
    const val TABLESPOON = "ст.л."
    const val TEASPOON = "ч.л."
    const val GLASS = "стакан"
    const val SLICE = "кусок"
    const val HANDFUL = "горсть"
    const val PACK = "пачка"
    const val CAN = "банка"
    const val BOTTLE = "бутылка"
    const val SCOOP = "шарик"
    const val SKEWER = "шампур"
    const val BUNCH = "пучок"

    /** Псевдоединица: «порция», «тарелка» — берём `default_portion_g` продукта. */
    const val PORTION = "порция"
}

private const val ML_PER_LITRE = 1000

/**
 * Ниже этого числа голое число без единицы не считается граммами.
 *
 * «курица 150» — это 150 грамм. «2 яйца» — это две штуки, а не два грамма.
 * Граница грубая, но ошибается в безопасную сторону: в непонятном случае
 * подставится типичная порция, а не двухграммовое яйцо.
 */
private const val BARE_GRAMS_MIN = 20

/** Сегментация фразы: запятая, точка с запятой, перевод строки, «и», «плюс», «+». */
private val SEGMENT_SPLIT = Regex("[,;\\n]+|\\s+и\\s+|\\s+плюс\\s+|\\s*\\+\\s*")

/** Число: «200», «0.5», «1,5». */
private val NUMBER = Regex("^\\d+(?:[.,]\\d+)?$")

/** Процент жирности — это часть названия, а не количество: «творог 5%», «молоко 3.2%». */
private val PERCENT = Regex("\\d+(?:[.,]\\d+)?\\s*%")

private val WORD_NUMBERS: Map<String, Double> = mapOf(
    "пол" to 0.5, "половина" to 0.5, "половину" to 0.5, "половинка" to 0.5,
    "полтора" to 1.5, "полторы" to 1.5,
    "один" to 1.0, "одна" to 1.0, "одно" to 1.0, "одну" to 1.0,
    "пара" to 2.0, "пары" to 2.0, "пару" to 2.0,
    "два" to 2.0, "две" to 2.0, "двe" to 2.0,
    "три" to 3.0, "четыре" to 4.0, "пять" to 5.0, "шесть" to 6.0,
    "семь" to 7.0, "восемь" to 8.0, "девять" to 9.0, "десять" to 10.0,
)

private val VAGUE: Map<String, Double> = mapOf(
    "немного" to 0.5, "немножко" to 0.4, "чуть" to 0.4, "чуточку" to 0.3,
    "капельку" to 0.25, "капля" to 0.25, "щепотка" to 0.1, "щепотку" to 0.1,
    "много" to 1.5, "побольше" to 1.5, "поменьше" to 0.6,
    "большая" to 1.5, "большой" to 1.5, "большую" to 1.5,
    "маленькая" to 0.6, "маленький" to 0.6, "маленькую" to 0.6,
)

/** Единицы веса и объёма: сразу превращаются в граммы. */
private val WEIGHT_UNITS: Map<String, Int> = buildMap {
    listOf("г", "гр", "g", "грамм", "грамма", "граммов", "грамов", "gram", "grams").forEach { put(it, 1) }
    listOf("кг", "kg", "килограмм", "килограмма", "килограммов").forEach { put(it, 1000) }
    // 1 мл ≈ 1 г. Для воды, молока и соков ошибка в пределах 5% — приемлемо,
    // для масла занижает на 8%. Точность тут не стоит отдельной таблицы плотностей.
    listOf("мл", "ml", "миллилитр", "миллилитра", "миллилитров").forEach { put(it, 1) }
    listOf("л", "l", "литр", "литра", "литров").forEach { put(it, ML_PER_LITRE) }
}

/** Порционные единицы: граммовка зависит от продукта и берётся из `portion_unit`. */
private val PORTION_UNITS: Map<String, String> = buildMap {
    listOf("шт", "штука", "штуки", "штук", "штуку", "штучка").forEach { put(it, Units.PIECE) }
    listOf("стл", "ложка", "ложки", "ложек", "ложку", "ложечка").forEach { put(it, Units.TABLESPOON) }
    listOf("чл", "чайная").forEach { put(it, Units.TEASPOON) }
    listOf("стакан", "стакана", "стаканов", "стаканчик",
        "кружка", "кружки", "кружку", "бокал", "бокала", "шейкер").forEach { put(it, Units.GLASS) }
    listOf("кусок", "куска", "кусков", "кусочек", "кусочка", "кусочков",
        "ломтик", "ломтика", "ломтиков", "долька", "дольки").forEach { put(it, Units.SLICE) }
    listOf("горсть", "горсти", "горстка", "горстку", "пригоршня").forEach { put(it, Units.HANDFUL) }
    listOf("пачка", "пачки", "пачку", "пачек").forEach { put(it, Units.PACK) }
    listOf("банка", "банки", "банку", "банок", "баночка", "баночку").forEach { put(it, Units.CAN) }
    listOf("бутылка", "бутылки", "бутылку", "бутылок").forEach { put(it, Units.BOTTLE) }
    listOf("шарик", "шарика", "шариков").forEach { put(it, Units.SCOOP) }
    listOf("шампур", "шампура", "шампуров").forEach { put(it, Units.SKEWER) }
    listOf("пучок", "пучка", "пучков").forEach { put(it, Units.BUNCH) }
    listOf("порция", "порции", "порций", "порцию", "тарелка", "тарелки", "тарелку",
        "миска", "миски", "миску", "пиала", "пиалу").forEach { put(it, Units.PORTION) }
}

/**
 * Многословные написания единиц схлопываются в один токен ДО разбиения на слова:
 * «столовая ложка» и «ст.л.» — одно и то же, но точки не переживают токенизацию.
 */
private val UNIT_PHRASES: List<Pair<Regex, String>> = listOf(
    Regex("столов\\w*\\s+ложк\\w*") to " стл ",
    Regex("чайн\\w*\\s+ложк\\w*") to " чл ",
    Regex("(?<![а-я])ст\\s*\\.?\\s*л\\s*\\.?(?![а-я])") to " стл ",
    Regex("(?<![а-я])ч\\s*\\.?\\s*л\\s*\\.?(?![а-я])") to " чл ",
    Regex("(?<![а-я])шт\\s*\\.") to " шт ",
    // «пол-ложки» -> «пол ложки»
    Regex("(?<![а-я])пол-") to " пол ",
    // «полстакана», «полложки» — слитное написание встречается чаще дефисного.
    Regex("(?<![а-я])пол(?=(?:стакан|ложк|банк|пачк|бутылк|кус|порци|тарелк|шт))") to "пол ",
)

private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{Nd}.,%]+")

/** Десятичная запятая внутри числа: «1,5» — одно число, а не граница сегмента. */
private val DECIMAL_COMMA = Regex("(\\d),(\\d)")

/**
 * Разбивает фразу на сегменты и разбирает каждый. Пустые сегменты выбрасываются.
 *
 * Запятая служит и разделителем перечисления, и десятичным разделителем. Перед
 * разбиением вторая роль снимается: иначе «1,5 стакана молока» распадётся
 * на «1» и «5 стакана молока».
 */
fun parsePhrase(text: String): List<ParsedSegment> =
    DECIMAL_COMMA.replace(text, "$1.$2")
        .split(SEGMENT_SPLIT)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull(::parseSegment)

/**
 * Разбирает один сегмент в «название + количество».
 *
 * Порядок слов не важен: «200 г гречки» и «гречка 200 г» разбираются одинаково.
 * Первое встреченное число и первая встреченная единица выигрывают, остальные слова
 * складываются в название.
 */
fun parseSegment(segment: String): ParsedSegment? {
    var text = normalizeForSearch(segment)
    // Жирность продукта числом не является — убираем до разбора количеств.
    text = PERCENT.replace(text, " ")
    for ((pattern, replacement) in UNIT_PHRASES) {
        text = pattern.replace(text, replacement)
    }

    val tokens = text.split(TOKEN_SPLIT)
        .map { it.trim('.', ',', '%') }
        .filter { it.isNotBlank() }

    var number: Double? = null
    /**
     * Число пришло словом («пол», «полтора»), а не цифрами.
     *
     * Различие важно на дробях: «пол банана» — это честная половина, а «творог 3.2»
     * без единицы — почти наверняка жирность. Цифрам на дробях мы не верим, словам верим.
     */
    var numberFromWord = false
    var weightGrams: Int? = null
    var portionUnit: String? = null
    var vague: Double? = null
    val nameTokens = mutableListOf<String>()

    for (token in tokens) {
        when {
            number == null && NUMBER.matches(token) ->
                number = token.replace(',', '.').toDoubleOrNull()

            number == null && WORD_NUMBERS.containsKey(token) -> {
                number = WORD_NUMBERS[token]
                numberFromWord = true
            }

            weightGrams == null && portionUnit == null && WEIGHT_UNITS.containsKey(token) ->
                weightGrams = WEIGHT_UNITS[token]

            weightGrams == null && portionUnit == null && PORTION_UNITS.containsKey(token) ->
                portionUnit = PORTION_UNITS[token]

            vague == null && VAGUE.containsKey(token) ->
                vague = VAGUE[token]

            // Числа, не ставшие количеством, в название не идут: FTS-поиск по «3.2»
            // ничего не найдёт и заодно отсечёт по AND всё остальное.
            NUMBER.matches(token) -> Unit

            else -> nameTokens += token
        }
    }

    val name = nameTokens.joinToString(" ")
    if (name.isBlank()) return null

    val quantity = when {
        weightGrams != null -> {
            val grams = ((number ?: 1.0) * weightGrams).toInt()
            if (grams > 0) Quantity.Weight(grams) else Quantity.None
        }
        portionUnit != null -> Quantity.Units(number ?: 1.0, portionUnit)
        // Дробное число ЦИФРАМИ без единицы — почти всегда жирность («творог 5.5»),
        // а не «полтора продукта». Словесная дробь («пол банана») сомнений не вызывает.
        number != null && (numberFromWord || number == Math.floor(number)) ->
            Quantity.Bare(number)
        vague != null -> Quantity.Vague(vague)
        else -> Quantity.None
    }

    return ParsedSegment(raw = segment.trim(), name = name, quantity = quantity)
}

/**
 * Во что превратилось количество.
 *
 * @param value граммы
 * @param guessed вес не был назван человеком, подставлена типичная порция
 * @param unit каноническая единица, в которой человек назвал количество, либо `null`.
 *        Не для подсчёта, а для памяти личных порций: только по названной человеком
 *        единице можно понять, что «тарелка» у него весит 400 г. Подставленный
 *        нами вес единицей не сопровождается — иначе модель выучила бы
 *        собственную догадку и закрепила её навсегда.
 * @param count сколько этих единиц названо
 */
data class Grams(
    val value: Int,
    val guessed: Boolean,
    val unit: String? = null,
    val count: Double = 1.0,
)

/**
 * Превращает разобранное количество в граммы, зная порционные единицы продукта.
 *
 * @param units граммовки порционных единиц ЭТОГО продукта: горсть орехов — 30 г,
 *              горсть ягод — 70 г, поэтому таблица у каждого своя
 * @param defaultPortionG типичная разовая порция продукта
 */
fun Quantity.toGrams(units: Map<String, Int>, defaultPortionG: Int?): Grams {
    val portion = defaultPortionG?.takeIf { it > 0 } ?: FALLBACK_PORTION_G

    return when (this) {
        // Явный вес — это наблюдение о типичной порции: человек раз за разом
        // пишет «творог 250», значит для него порция творога и есть 250 г.
        is Quantity.Weight -> Grams(grams, guessed = false, unit = Units.PORTION)

        is Quantity.Units -> {
            val perUnit = if (unit == Units.PORTION) portion else units[unit]
            if (perUnit != null) {
                Grams(Math.round(count * perUnit).toInt(), guessed = false, unit = unit, count = count)
            } else {
                // Единица названа, но для этого продукта её перевода нет
                // («стакан котлет»). Не выбрасываем сегмент — берём порцию.
                Grams(Math.round(count * portion).toInt(), guessed = true)
            }
        }

        is Quantity.Bare -> {
            val pieceGrams = units[Units.PIECE]
            when {
                // «2 яйца», «3 банана» — счёт штук, пока число похоже на счёт.
                pieceGrams != null && count <= PIECE_COUNT_MAX ->
                    Grams(
                        Math.round(count * pieceGrams).toInt(),
                        guessed = false,
                        unit = Units.PIECE,
                        count = count,
                    )
                // «курица 150» — это граммы.
                count >= BARE_GRAMS_MIN ->
                    Grams(count.toInt(), guessed = false, unit = Units.PORTION)
                // «молоко 3» — ни то, ни другое. Типичная порция честнее трёх грамм.
                else -> Grams(portion, guessed = true)
            }
        }

        is Quantity.Vague ->
            Grams(Math.round(portion * factor).toInt().coerceAtLeast(1), guessed = true)

        Quantity.None -> Grams(portion, guessed = true)
    }
}

/** Сколько штук ещё считается счётом, а не граммами. Дюжина яиц — предел разумного. */
private const val PIECE_COUNT_MAX = 12

/** Когда у продукта нет типичной порции — последняя линия обороны. */
private const val FALLBACK_PORTION_G = 100
