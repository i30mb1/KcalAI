package n7.kcalai.repositories

import n7.kcalai.model.Nutriments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка КБЖУ, введённого руками.
 *
 * Две ступени и разница между ними: ошибка — невозможное, сохранять нельзя;
 * предупреждение — странное, но напечатанное на упаковке, и спорить с ней нельзя.
 */
class NutrimentValidatorTest {

    @Test
    fun `обычный продукт проходит без замечаний`() {
        // Молоко 3,2%: 59 ккал, Б 2,9 / Ж 3,2 / У 4,7.
        val check = NutrimentValidator.check(Nutriments(59, 290, 320, 470))

        assertTrue(check.valid)
        assertNull(check.warning)
    }

    @Test
    fun `отрицательные значения — ошибка`() {
        assertFalse(NutrimentValidator.check(Nutriments(100, -1, 0, 0)).valid)
    }

    @Test
    fun `калорий больше, чем у чистого жира, не бывает`() {
        assertFalse(NutrimentValidator.check(Nutriments(901, 0, 10_000, 0)).valid)
        assertTrue(NutrimentValidator.check(Nutriments(900, 0, 10_000, 0)).valid)
    }

    @Test
    fun `макросы вместе не помещаются в сто грамм`() {
        assertFalse(NutrimentValidator.check(Nutriments(400, 5_000, 3_000, 3_000)).valid)
    }

    @Test
    fun `потерянный разряд в калориях ловится предупреждением, а не ошибкой`() {
        // Гречка: Б 12,6 / Ж 3,3 / У 62 дают ~330 ккал, а введено 33.
        val check = NutrimentValidator.check(Nutriments(33, 1_260, 330, 6_200))

        assertTrue("странное — не невозможное, сохранить можно", check.valid)
        assertNotNull(check.warning)
    }

    @Test
    fun `расхождение в пределах четверти не считается ошибкой`() {
        // Клетчатка и округление на этикетке дают именно такие расхождения.
        val check = NutrimentValidator.check(Nutriments(300, 1_000, 500, 5_000))

        assertNull(check.warning)
    }

    @Test
    fun `у зелени и напитков баланс не сверяется`() {
        // 15 ккал с нулевыми макросами — огурец, а не опечатка.
        assertNull(NutrimentValidator.check(Nutriments(15, 0, 0, 0)).warning)
    }

    @Test
    fun `калории по Этуотеру считаются из сотых грамма`() {
        // 10 г белка, 10 г жира, 10 г углеводов = 40 + 90 + 40.
        assertEquals(170.0, NutrimentValidator.atwaterKcal(Nutriments(0, 1_000, 1_000, 1_000)), 0.0)
    }
}
