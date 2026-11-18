package n7.kcalai.assistant

import n7.kcalai.model.Log
import n7.kcalai.resolver.ResolvedItem

/**
 * Что и как говорят нейросети — одно на Android и iOS.
 *
 * Механика у моделей разная: Gemini Nano сама зовёт инструменты (`*_WITH_TOOLS`),
 * моделям Apple найденное и дневник подаются заранее (`PARSE_FROM_LIST`,
 * `ANSWER_FROM_DIARY`). Но правила собраны из одних кусков — [PORTIONS] и [ANSWER_RULES]:
 * иначе «тарелка борща» на двух телефонах весила бы по-разному.
 *
 * По-английски: маленькие модели инструкции на нём слушаются лучше, а отвечают по-русски.
 */
object AssistantPrompts {

    /** Сколько весит названное словами. Одна таблица на все модели. */
    val PORTIONS = """
        Estimate how many grams were eaten from the phrase. Typical sizes:
        tablespoon 15 g, teaspoon 5 g, glass or cup 200 g, mug 250 g,
        plate of soup 300 g, plate of porridge or pasta 250 g, slice of bread 30 g,
        piece of cake 100 g. "half" and "a bit" reduce the amount.
        Without any hint, use the portion from the search line.
    """.trimIndent()

    /** Как отвечать на вопрос о дневнике. Одни правила на все модели. */
    val ANSWER_RULES = """
        Reply in Russian, in one to three short sentences, addressing the user informally ("ты").
        Use only the numbers you are given. Do not compute calories yourself beyond adding a few numbers.
        If the diary has no data for the question, say so.
        Give no medical advice and no diets.
    """.trimIndent()

    /** Разбор фразы моделью, которая сама зовёт `search_food` и `propose_food`. */
    val PARSE_WITH_TOOLS = """
        |You turn a phrase from a Russian food diary into diary entries.
        |For every dish or drink in the phrase:
        |1. Call search_food with a short Russian name of it: 1-3 words, nominative case.
        |   If nothing is found, try once more with a simpler or more generic name.
        |2. Pick the best matching line. Its first field is the foodId.
        |3. $PORTIONS
        |4. Call propose_food with that foodId, the grams, and phrase: the words of
        |   the user text that describe this dish.
        |Use only foodIds returned by search_food. Never invent foodIds or nutrition.
        |If no line fits a dish, skip that dish. Do not write a text answer.
    """.trimMargin()

    /** Ответ на вопрос моделью, которая сама зовёт инструменты дневника. */
    val ANSWER_WITH_TOOLS = """
        |You answer questions about the user's food diary.
        |Tools: diary_days gives totals per day and what is left of today's goal;
        |day_entries lists what was eaten on a day; search_food gives nutrition per 100 g.
        |$ANSWER_RULES
    """.trimMargin()

    /** Разбор фразы, когда найденное подано списком, а инструментов у модели нет. */
    val PARSE_FROM_LIST = """
        |You turn a phrase from a Russian food diary into diary entries.
        |You get the phrase and the products found for it, one per line; the first field of a line is its key.
        |For every dish or drink in the phrase pick the best matching line.
        |$PORTIONS
        |Answer only with JSON like [{"key":"generic:7","grams":120}] and nothing else.
        |Use only keys from the list. Never invent keys or nutrition. If nothing fits, answer [].
    """.trimMargin()

    /** Ответ на вопрос, когда итоги дневника поданы вместе с вопросом. */
    val ANSWER_FROM_DIARY = """
        |You answer questions about the user's food diary. Its totals come with the question.
        |$ANSWER_RULES
    """.trimMargin()

    /** Запрос к [PARSE_FROM_LIST]: фраза и найденное по ней — строки [DiaryTools.searchFood]. */
    fun parsePrompt(phrase: String, found: String): String = "Phrase: $phrase\nFound products:\n$found"

    /** Запрос к [ANSWER_FROM_DIARY]: итоги дней — строки [DiaryTools.diaryDays], — и вопрос. */
    fun answerPrompt(question: String, diary: String): String = "Diary:\n$diary\n\nQuestion: $question"
}

/**
 * Ответ модели на [AssistantPrompts.PARSE_FROM_LIST] -> чипсы.
 *
 * Собирает их тот же [DiaryTools.proposal], что и вызовы Gemini Nano, и с тем же именем
 * ключа — `foodId`: ключ, которого поиск не отдавал, — выдуманный, и продукта за ним нет.
 *
 * @param phrase что набрал человек: чипс показывает его слова
 */
suspend fun proposalsFrom(json: String, phrase: String, tools: DiaryTools): List<ResolvedItem> =
    parseProposals(json).mapNotNull { (key, grams) ->
        tools.proposal(DiaryTools.PROPOSE, mapOf("foodId" to key, "grams" to grams, "phrase" to phrase))
    }.distinctBy { it.candidate?.ref }

/**
 * JSON предложений -> пары «ключ, граммы».
 *
 * Руками, а не сериализацией: ответ модели — не наш формат, а её попытка его
 * повторить, и половина разбора здесь это терпимость к тому, как она ошибается.
 * Битый ответ — пустой список, а не исключение: модель не поняла — это штатно.
 */
internal fun parseProposals(json: String): List<Pair<String, Int>> = runCatching {
    OBJECT.findAll(json).mapNotNull { match ->
        val body = match.value
        val key = KEY.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
        val grams = GRAMS.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        key to grams
    }.toList()
}.getOrElse {
    Log.w("AssistantPrompts", "ответ модели не разобран: $it")
    emptyList()
}

private val OBJECT = Regex("""\{[^{}]*}""")
private val KEY = Regex(""""key"\s*:\s*"([^"]+)"""")
private val GRAMS = Regex(""""grams"\s*:\s*(\d+)""")
