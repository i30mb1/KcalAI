package n7.kcalai.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Nutriments

/**
 * Что говорят нейросети — одинаково на Android и iOS.
 *
 * Механика у моделей разная: Gemini Nano сама зовёт инструменты, модели Apple поиск
 * подают заранее. Но правила — сколько весит тарелка супа, на «ты», без диет —
 * одни и собираются из одних кусков: иначе «тарелка борща» на двух телефонах
 * весила бы по-разному.
 */
class AssistantPromptsTest {

    @Test
    fun `обе модели знают одни и те же порции`() {
        assertTrue("plate of soup 300 g" in AssistantPrompts.PORTIONS)
        assertTrue(AssistantPrompts.PORTIONS in AssistantPrompts.PARSE_WITH_TOOLS)
        assertTrue(AssistantPrompts.PORTIONS in AssistantPrompts.PARSE_FROM_LIST)
    }

    @Test
    fun `обе модели отвечают по одним правилам`() {
        assertTrue("informally" in AssistantPrompts.ANSWER_RULES)
        assertTrue("no diets" in AssistantPrompts.ANSWER_RULES)
        assertTrue(AssistantPrompts.ANSWER_RULES in AssistantPrompts.ANSWER_WITH_TOOLS)
        assertTrue(AssistantPrompts.ANSWER_RULES in AssistantPrompts.ANSWER_FROM_DIARY)
    }

    @Test
    fun `запрос на разбор несёт фразу и найденное`() {
        val prompt = AssistantPrompts.parsePrompt("тарелка борща", "generic:12 | Борщ")

        assertTrue("тарелка борща" in prompt && "generic:12 | Борщ" in prompt, prompt)
    }

    @Test
    fun `вопрос несёт дневник и сам вопрос`() {
        val prompt = AssistantPrompts.answerPrompt("сколько белка?", "сегодня: 520 ккал")

        assertTrue("сколько белка?" in prompt && "сегодня: 520 ккал" in prompt, prompt)
    }

    /** Ключ продукта уходит в DiaryTools тем же именем, что у Gemini Nano, — `foodId`. */
    @Test
    fun `ответ модели становится предложением только с ключом из поиска`() = runTest {
        val tools = DiaryTools(search = { _, _ -> listOf(borscht) }, day = { emptyList() }, goal = { null }, today = { 0 })
        tools.searchFood("борщ")

        val picks = proposalsFrom(
            json = """[{"key":"generic:12","grams":300},{"key":"generic:999","grams":100}]""",
            phrase = "тарелка борща",
            tools = tools,
        )

        assertEquals(listOf("Борщ" to 300), picks.map { it.candidate?.displayName to it.grams })
        assertEquals("тарелка борща", picks.single().sourceText)
    }

    private val borscht = FoodCandidate(
        ref = FoodRef.Generic(12),
        displayName = "Борщ",
        nutriments = Nutriments(kcal100 = 49, prot100 = 110, fat100 = 220, carb100 = 670),
        servingG = 300,
    )
}
