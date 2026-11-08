package n7.kcalai.model

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class NutrimentsTest {

    private val buckwheat = Nutriments(kcal100 = 132, prot100 = 456, fat100 = 110, carb100 = 2500)

    /** Гречка сухая: 11,3 г клетчатки на 100 г — отдельно от углеводов, как в русских таблицах. */
    private val buckwheatDry = Nutriments(kcal100 = 308, prot100 = 1260, fat100 = 330, carb100 = 5710, fiber100 = 1130)

    @Test
    fun `клетчатка считается на порцию так же, как БЖУ`() {
        assertEquals(FiberTotal(cg = 565, unknown = 0), buckwheatDry.fiberFor(50))
    }

    /** У котлеты без данных клетчатка не ноль, а неизвестна: итог дня тогда «не меньше». */
    @Test
    fun `неизвестная клетчатка — пробел в сумме, а не ноль`() {
        val cutlet = Nutriments(kcal100 = 250, prot100 = 1500, fat100 = 1800, carb100 = 900)

        val day = buckwheatDry.fiberFor(100) + cutlet.fiberFor(100)

        assertEquals(1130, day.cg)
        assertEquals(1, day.unknown)
        assertFalse(day.complete)
    }

    @Test
    fun `в пустом дне клетчатки ноль, и это известно`() {
        assertTrue(FiberTotal.ZERO.complete)
        assertEquals(buckwheatDry.fiberFor(100), FiberTotal.ZERO + buckwheatDry.fiberFor(100))
    }

    @Test
    fun `сто грамм дают исходные значения`() {
        val totals = buckwheat.forGrams(100)

        assertEquals(132, totals.kcal)
        assertEquals(456, totals.protCg)
        assertEquals(110, totals.fatCg)
        assertEquals(2500, totals.carbCg)
    }

    @Test
    fun `пятьдесят грамм дают половину`() {
        val totals = buckwheat.forGrams(50)

        assertEquals(66, totals.kcal)
        assertEquals(228, totals.protCg)
        assertEquals(55, totals.fatCg)
        assertEquals(1250, totals.carbCg)
    }

    @Test
    fun `ноль грамм даёт нули`() {
        val totals = buckwheat.forGrams(0)

        assertEquals(NutrimentTotals.ZERO, totals)
    }

    @Test
    fun `дробный результат округляется вверх от половины`() {
        // 101 ккал/100 г * 50 г = 50.5 -> 51
        val totals = Nutriments(kcal100 = 101, prot100 = 0, fat100 = 0, carb100 = 0).forGrams(50)

        assertEquals(51, totals.kcal)
    }

    @Test
    fun `дробный результат округляется вниз ниже половины`() {
        // 100 ккал/100 г * 49 г = 49.0; 99 * 49 = 48.51 -> 49
        val totals = Nutriments(kcal100 = 99, prot100 = 0, fat100 = 0, carb100 = 0).forGrams(49)

        assertEquals(49, totals.kcal)
    }

    @Test
    fun `большая порция не переполняет Int`() {
        // Произведение считается в Long: 10000 сг * 1 000 000 г это 10^10,
        // в Int не влезает, а сам результат — 10^8 сг — влезает с запасом.
        val totals = Nutriments(kcal100 = 900, prot100 = 10000, fat100 = 10000, carb100 = 10000)
            .forGrams(1_000_000)

        assertEquals(9_000_000, totals.kcal)
        assertEquals(100_000_000, totals.protCg)
    }

    @Test
    fun `отрицательная масса отвергается`() {
        assertFailsWith<IllegalArgumentException> {
            buckwheat.forGrams(-1)
        }
    }

    @Test
    fun `суммы складываются`() {
        val a = buckwheat.forGrams(100)
        val b = buckwheat.forGrams(50)

        val sum = a + b

        assertEquals(198, sum.kcal)
        assertEquals(684, sum.protCg)
        assertEquals(165, sum.fatCg)
        assertEquals(3750, sum.carbCg)
    }

    @Test
    fun `ноль нейтрален при сложении`() {
        val a = buckwheat.forGrams(100)

        // Обе стороны: ZERO действительно нулевой и слагаемое не теряется.
        assertEquals(a, a + NutrimentTotals.ZERO)
        assertEquals(a, NutrimentTotals.ZERO + a)
    }
}
