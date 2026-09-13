package n7.kcalai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NutrimentsTest {

    private val buckwheat = Nutriments(kcal100 = 132, prot100 = 456, fat100 = 110, carb100 = 2500)

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
        val totals = Nutriments(kcal100 = 900, prot100 = 10000, fat100 = 10000, carb100 = 10000)
            .forGrams(100_000)

        assertEquals(900_000, totals.kcal)
        assertEquals(10_000_000, totals.protCg)
    }

    @Test
    fun `отрицательная масса отвергается`() {
        assertThrows(IllegalArgumentException::class.java) {
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
    }

    @Test
    fun `ноль нейтрален при сложении`() {
        val a = buckwheat.forGrams(100)

        assertEquals(a, a + NutrimentTotals.ZERO)
    }
}
