package n7.kcalai.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Кто из троих отвечает за продукт.
 *
 * Правило одно на всё приложение: точка на чипсе, персонаж на чипсе, гордость
 * за записанное и подпись в уведомлении берут хозяина из одного места.
 */
class MascotTest {

    private fun food(prot: Int, fat: Int, carb: Int) =
        Nutriments(kcal100 = 100, prot100 = prot, fat100 = fat, carb100 = carb)

    @Test
    fun `хозяин продукта — макрос, которого больше по граммам`() {
        assertEquals(Mascot.EGG, food(prot = 1_700, fat = 500, carb = 300).owner(), "творог")
        assertEquals(Mascot.BUTTER, food(prot = 100, fat = 8_200, carb = 100).owner(), "масло")
        assertEquals(Mascot.GRAIN, food(prot = 420, fat = 110, carb = 2_130).owner(), "гречка")
    }

    /** По калориям жир выигрывал бы почти всегда: девять килокалорий на грамм против четырёх. */
    @Test
    fun `граммы, а не калории`() {
        assertEquals(Mascot.GRAIN, food(prot = 0, fat = 900, carb = 1_000).owner())
    }

    @Test
    fun `ничья достаётся белку, потом жиру`() {
        assertEquals(Mascot.EGG, food(prot = 500, fat = 500, carb = 500).owner())
        assertEquals(Mascot.BUTTER, food(prot = 100, fat = 500, carb = 500).owner())
    }

    @Test
    fun `у порции хозяин тот же, что у продукта`() {
        val totals = NutrimentTotals(kcal = 300, protCg = 3_400, fatCg = 1_000, carbCg = 600)

        assertEquals(Mascot.EGG, totals.owner())
    }

    @Test
    fun `имена троих`() {
        assertEquals(listOf("Яйцо", "Масло", "Зёрнышко"), Mascot.entries.map { it.title })
    }
}
