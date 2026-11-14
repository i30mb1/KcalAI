package n7.kcalai.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Разбор того, что ответила модель.
 *
 * Её ответ — не наш формат, а попытка его повторить: она добавляет пояснения
 * до и после, путает кавычки, иногда отвечает одним объектом вместо массива.
 * Непонятое — пустой список, а не исключение: «модель не поняла» здесь штатный
 * исход, из-за которого нельзя ронять ленту.
 */
class ProposalParsingTest {

    @Test
    fun `массив предложений разбирается`() {
        val parsed = parseProposals("""[{"key":"generic:7","grams":200},{"key":"user:3","grams":55}]""")

        assertEquals(listOf("generic:7" to 200, "user:3" to 55), parsed)
    }

    /** Модель любит объяснять, что сделала, до и после JSON. */
    @Test
    fun `пояснения вокруг JSON не мешают`() {
        val parsed = parseProposals("""Вот что я нашла: [{"key":"generic:7","grams":200}] — подтверди.""")

        assertEquals(listOf("generic:7" to 200), parsed)
    }

    @Test
    fun `одиночный объект без массива тоже считается`() {
        assertEquals(listOf("generic:1" to 100), parseProposals("""{"key":"generic:1","grams":100}"""))
    }

    @Test
    fun `объект без граммов пропускается`() {
        val parsed = parseProposals("""[{"key":"generic:7"},{"key":"generic:8","grams":120}]""")

        assertEquals(listOf("generic:8" to 120), parsed)
    }

    @Test
    fun `битый ответ — пустой список а не падение`() {
        assertTrue(parseProposals("не знаю такого").isEmpty())
        assertTrue(parseProposals("").isEmpty())
        assertTrue(parseProposals("[{]").isEmpty())
    }

    /** Граммы дробные или строкой — не наш формат: такому предложению верить нельзя. */
    @Test
    fun `нечисловые граммы пропускаются`() {
        assertTrue(parseProposals("""[{"key":"generic:7","grams":"много"}]""").isEmpty())
    }
}
