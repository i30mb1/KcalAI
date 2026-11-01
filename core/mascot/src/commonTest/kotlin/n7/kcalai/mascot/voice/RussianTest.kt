package n7.kcalai.mascot.voice

import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Числа и названия так, как их пишет человек в переписке.
 *
 * «3 ч 20 мин без еды» — так пишет приложение. Друг пишет «уже три часа».
 */
class RussianTest {

    @Test
    fun `часы словами и вниз до получаса`() {
        assertEquals("меньше часа", hoursPhrase(40))
        assertEquals("час", hoursPhrase(60))
        assertEquals("час", hoursPhrase(89))
        assertEquals("полтора часа", hoursPhrase(90))
        assertEquals("два часа", hoursPhrase(120))
        assertEquals("два с половиной часа", hoursPhrase(150))
        assertEquals("три часа", hoursPhrase(200))
        assertEquals("пять часов", hoursPhrase(300))
        assertEquals("пять с половиной часов", hoursPhrase(330))
        assertEquals("двенадцать часов", hoursPhrase(720))
        assertEquals("больше двенадцати часов", hoursPhrase(800))
    }

    @Test
    fun `дни отсутствия — как сказал бы друг`() {
        assertEquals("день", daysPhrase(1))
        assertEquals("два дня", daysPhrase(2))
        assertEquals("четыре дня", daysPhrase(4))
        assertEquals("пять дней", daysPhrase(5))
        assertEquals("неделю", daysPhrase(7))
        assertEquals("больше недели", daysPhrase(10))
        assertEquals("две недели", daysPhrase(14))
        assertEquals("больше двух недель", daysPhrase(20))
        assertEquals("больше месяца", daysPhrase(45))
    }

    @Test
    fun `счёт словами до десяти, дальше цифрой`() {
        assertEquals("один день", countPhrase(1, "день", "дня", "дней"))
        assertEquals("три дня", countPhrase(3, "день", "дня", "дней"))
        assertEquals("шесть дней", countPhrase(6, "день", "дня", "дней"))
        assertEquals("11 дней", countPhrase(11, "день", "дня", "дней"))
        assertEquals("21 день", countPhrase(21, "день", "дня", "дней"))
        assertEquals("две недели", countPhrase(2, "неделя", "недели", "недель", feminine = true))
    }

    @Test
    fun `порядковые словами`() {
        assertEquals("второй", ordinal(2))
        assertEquals("третий", ordinal(3))
        assertEquals("четвёртый", ordinal(4))
        assertEquals("десятый", ordinal(10))
        assertEquals("11-й", ordinal(11))
    }

    @Test
    fun `обычное название — со строчной`() {
        assertEquals("гречка отварная", foodName("Гречка отварная"))
        assertEquals("гречка отварная", foodName("  Гречка   отварная "))
    }

    /** Капс и латиница — это бренд, и его не трогают. */
    @Test
    fun `бренд остаётся как есть`() {
        assertEquals("КЕФИР 1%", foodName("КЕФИР 1%"))
        assertEquals("Activia йогурт", foodName("Activia йогурт"))
    }

    @Test
    fun `длинное название режется по слову`() {
        val name = foodName("Творог мягкий обезжиренный «Простоквашино» в ванночке")

        assertEquals("творог мягкий обезжиренный", name)
    }

    @Test
    fun `обрезка не оставляет висящих кавычек и знаков`() {
        val name = foodName("Сырок «Б.Ю. Александров», глазированный в шоколаде")

        assertTrue(name.length <= 32, name)
        assertFalse(name.endsWith(","), name)
        assertEquals(name.count { it == '«' }, name.count { it == '»' }, name)
    }

    @Test
    fun `день недели и время`() {
        assertEquals("пятница", weekdayName(DayOfWeek.FRIDAY))
        assertEquals("воскресенье", weekdayName(DayOfWeek.SUNDAY))
        assertEquals("09:05", clock(9, 5))
        assertEquals("13:40", clock(13, 40))
    }
}
