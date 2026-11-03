package n7.kcalai.feature.scanner

import n7.kcalai.repositories.LabelParser

/**
 * Название с пачки — в запрос к справочнику.
 *
 * Кандидатов в названия уже отобрал разбор этикетки (см. [LabelParser.nameCandidates])
 * и упорядочили голоса кадров. Но отобраны они для поля «название», где человек
 * сам видит и правит строку, а здесь строка уходит в поиск, и требования к ней
 * другие. Поиск по словам соединяет их через «и»: «Творог 5% 180 г» не находит
 * «Творог 5%», потому что в справочнике нет слова «180». Поэтому из строки
 * уходит всё, что продуктом не является, — масса, пометки упаковки, кавычки
 * бренда, — а строки, которые названием быть не могут вовсе, отсеиваются
 * второй раз: имя в поле может быть и набрано руками, и снято с другого кадра.
 *
 * Бренд при этом остаётся. Справочник товаров его знает, а поиск по смыслу,
 * куда запрос уходит, когда по словам не нашлось ничего, лишнее слово терпит.
 */
internal object LabelNameQuery {

    /** Первый из кандидатов, из которого выходит запрос, — в порядке их уверенности. */
    fun pick(names: List<String>): String? = names.firstNotNullOfOrNull(::of)

    /** Запрос из одной строки — или `null`, если это не название или искать в нём нечего. */
    fun of(name: String): String? {
        if (LabelParser.isServiceText(name)) return null

        val stripped = name
            .replace(AMOUNT, " ")
            .replace(PACKAGE_MARKS, " ")
            .replace(QUOTES, " ")
        // Адрес и дата проверяются после того, как ушла масса: «180 г. Простоквашино»
        // иначе читалось бы как «г. Москва».
        if (ADDRESS.containsMatchIn(stripped) || DATE.containsMatchIn(stripped)) return null
        if (LIST_COMMA.findAll(stripped).count() >= LIST_COMMAS) return null

        val query = stripped
            .split(SPACES)
            .filter(String::isNotEmpty)
            .joinToString(" ") { it.toCyrillicWord() }
            .trim(',', '.', ':', ';', '-', '·', ' ')

        // Одна жирность или «С1» — не запрос: справочник ответит чем попало.
        if (query.split(' ').none { word -> word.count(Char::isLetter) >= MIN_WORD_LETTERS }) return null
        return query
    }

    /**
     * Слово с кириллицей — целиком кириллицей.
     *
     * Распознаватель выбирает между «О» и «O», «Р» и «P» по контексту и на капсе
     * ошибается: «TBOPOГ» на экране не отличить от «ТВОРОГ», а поиск по словам
     * не находит его вовсе. Ноль посреди букв — та же буква «о». Целиком латинское
     * слово не трогается: «Alpen Gold» так и напечатан.
     */
    private fun String.toCyrillicWord(): String {
        if (none { it in CYRILLIC }) return this
        return mapIndexed { i, char ->
            val between = char == '0' && getOrNull(i - 1)?.isLetter() == true && getOrNull(i + 1)?.isLetter() == true
            when {
                between -> if (this[i - 1].isUpperCase()) 'О' else 'о'
                else -> LATIN_TWINS[char] ?: char
            }
        }.joinToString("")
    }

    private val CYRILLIC = 'Ѐ'..'ӿ'

    /** Латинские буквы, которые на пачке неотличимы от русских. */
    private val LATIN_TWINS = mapOf(
        'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'K' to 'К', 'M' to 'М',
        'O' to 'О', 'P' to 'Р', 'T' to 'Т', 'X' to 'Х', 'Y' to 'У',
        'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'x' to 'х', 'y' to 'у',
    )

    /**
     * Масса и объём: «180 г», «180г», «0,9 л», «930 мл.», «10 шт». Латинская «r» —
     * это «г», прочитанная мимо. Проценты сюда не входят: «Творог 5%» и «Творог 9%» —
     * разные продукты справочника. Число, приросшее к букве, — не масса: в «ТВ0Р0Г»
     * это буква «о», и «0Г» иначе срезалось бы как «0 г».
     */
    private val AMOUNT = Regex(
        """(?<![\p{L}\d.,])\d+(?:[.,]\d+)?\s*(?:кг|гр|г|мл|л|шт|r)\.?(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE,
    )

    /** Пометки упаковки, а не слова названия: в справочнике нет ни «БЗМЖ», ни «м.д.ж.». */
    private val PACKAGE_MARKS = Regex(
        """(?<!\p{L})(?:бзмж|м\.?\s?д\.?\s?ж\.?)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )

    private val QUOTES = Regex("""["«»“”„']""")

    /**
     * Адрес изготовителя: почтовый индекс, «ул.», «обл.», «г. Москва», «д. 5»
     * и «сделано в». Страна сама по себе адресом не считается: «Российский» —
     * это сыр.
     */
    private val ADDRESS = Regex(
        """(?<!\d)\d{6}(?!\d)""" +
            """|(?<!\p{L})(?:ул|обл|пр-т|просп|р-н|пос|корп)\.""" +
            """|(?<!\p{L})г\.\s*\p{Lu}""" +
            """|(?<!\p{L})д\.\s*\d""" +
            """|сделано|произвед""",
        RegexOption.IGNORE_CASE,
    )

    /** «12.05.2026», «01/11/26» — дата изготовления или срок. */
    private val DATE = Regex("""\d{1,2}[./]\d{1,2}[./]\d{2,4}""")

    /** Запятая перечисления, а не дроби: в «2,5%» она стоит между цифрами. */
    private val LIST_COMMA = Regex(""",(?!\d)|(?<!\d),""")

    /** Две запятые перечисления — это состав, продолжившийся на следующую строку. */
    private const val LIST_COMMAS = 2

    private const val MIN_WORD_LETTERS = 3

    private val SPACES = Regex("""\s+""")
}
