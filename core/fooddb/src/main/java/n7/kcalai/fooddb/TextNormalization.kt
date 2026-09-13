package n7.kcalai.fooddb

/**
 * Нормализация текста для поиска.
 *
 * Правило обязано совпадать с `normalize()` в `tools/builddb/build_seed.py` — им
 * нормализуются алиасы при сборке индекса. Разойдутся правила — половина алиасов
 * перестанет находиться, причём молча.
 */
fun normalizeForSearch(text: String): String =
    text.trim().lowercase().replace('ё', 'е')

/**
 * Окончания русских словоизменительных форм, от длинных к коротким.
 *
 * Полноценный стеммер (Snowball) здесь избыточен: нам не нужна лингвистическая
 * корректность, нужно чтобы «гречки», «гречкой» и «гречка» сошлись в один префикс.
 */
private val RU_ENDINGS: List<String> = listOf(
    "иями", "ами", "ями", "иях", "ах", "ях",
    "ого", "его", "ому", "ему", "ыми", "ими", "ыми",
    "ая", "яя", "ое", "ее", "ые", "ие", "ую", "юю",
    "ой", "ей", "ый", "ий", "ом", "ем", "ам", "ям", "ов", "ев", "ью", "ия",
    "а", "я", "ы", "и", "у", "ю", "е", "о", "ь", "й",
).sortedByDescending { it.length }

/** Ниже этого порога окончание не срезается: «чай» не должен превратиться в «ча». */
private const val MIN_STEM = 3

/**
 * Грубое отсечение окончания. «гречки» -> «гречк», «яйца» -> «яйц», «чай» -> «чай».
 *
 * Ошибка в сторону недорезания безопасна (найдётся меньше), в сторону перерезания —
 * нет (найдётся лишнее), поэтому срезается ровно одно окончание и только если
 * основа остаётся не короче [MIN_STEM].
 */
fun stemRu(token: String): String {
    if (token.length <= MIN_STEM) return token
    for (ending in RU_ENDINGS) {
        if (token.length - ending.length >= MIN_STEM && token.endsWith(ending)) {
            return token.dropLast(ending.length)
        }
    }
    return token
}

private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{Nd}]+")

/** Разбивает нормализованный текст на слова, выбрасывая пунктуацию. */
fun tokenize(text: String): List<String> =
    normalizeForSearch(text).split(TOKEN_SPLIT).filter { it.isNotBlank() }

/**
 * Готовит пользовательский ввод для FTS5 MATCH.
 *
 * Каждое слово превращается в префиксный запрос по основе: `"гречк"*`. Кавычки
 * обязательны — без них любой спецсимвол или оператор FTS5 (`OR`, `NEAR`, `*`)
 * уронит запрос или изменит его смысл. Слова соединяются неявным AND, то есть
 * «молоко простоквашино» найдёт только то, где есть оба слова.
 *
 * @return null, если искать нечего
 */
fun ftsPrefixQuery(raw: String): String? {
    val tokens = tokenize(raw)
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { token ->
        val stem = stemRu(token).replace("\"", "")
        if (stem.isEmpty()) "" else "\"$stem\"*"
    }.trim().ifEmpty { null }
}
