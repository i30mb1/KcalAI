package n7.kcalai.feature.diary

import kotlinx.datetime.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Шапка после того, как ушла клавиатура.
 *
 * Клавиатура сворачивает шапку, и раньше та оставалась свёрнутой до жеста
 * «потянуть вниз», о котором никто не знает: утром, с одной репликой в ленте,
 * человек видел пустой экран без плиток и недели. Раскрыться ей можно, но лишь
 * когда лента и под раскрытой шапкой помещается целиком, — иначе последняя
 * реплика уехала бы за нижний край.
 */
class HeaderTest {

    @Test
    fun `шапка раскрывается когда под ней хватает места`() {
        assertTrue(headerFits(spare = 900, growth = 600))
        assertTrue(headerFits(spare = 600, growth = 600))
    }

    @Test
    fun `шапка не раскрывается если лента под ней перестанет помещаться`() {
        assertFalse(headerFits(spare = 300, growth = 600))
    }

    @Test
    fun `лента которая не помещается и так шапку не раскрывает`() {
        assertFalse(headerFits(spare = null, growth = 600))
    }

    // --- Крупное число шапки ----------------------------------------------------------

    @Test
    fun `сегодня крупно — сколько ещё можно`() {
        assertEquals(Headline(519, "осталось", over = false), headline(1581, 2100, past = false))
        assertEquals(Headline(310, "перебор", over = true), headline(2410, 2100, past = false))
    }

    /**
     * Про прошедший день «осталось 519» — неправда: оставаться больше нечему.
     * Крупно стоит съеденное, перебор — цветом числа.
     */
    @Test
    fun `в прошлом дне крупно — сколько съедено`() {
        assertEquals(Headline(1581, "съедено", over = false), headline(1581, 2100, past = true))
        assertEquals(Headline(2410, "съедено", over = true), headline(2410, 2100, past = true))
    }

    /**
     * Без цели — только съеденное. Цель задаётся тапом по самому числу, как и правится.
     */
    @Test
    fun `без цели — только съеденное`() {
        assertEquals(Headline(900, "съедено", over = false), headline(900, null, past = false))
        assertEquals(Headline(900, "съедено", over = false), headline(900, null, past = true))
    }

    // --- Фон шапки и строки ввода ------------------------------------------------------

    /**
     * Лента идёт под шапкой и под строкой ввода. Без матового стекла (до Android 17.2)
     * сквозь полупрозрачный фон реплики читались бы поверх цифр и набранного — фон
     * обязан быть плотным.
     */
    @Test
    fun `без стекла шапка и строка ввода непрозрачны`() {
        assertEquals(1f, glassScrimAlpha(frosted = false))
    }

    @Test
    fun `со стеклом сквозь них видна размытая лента`() {
        assertTrue(glassScrimAlpha(frosted = true) < 1f)
        assertTrue(glassScrimAlpha(frosted = true) > 0f)
    }

    /**
     * Дорога назад из прошлого дня — стрелка в самой дате, а не отдельная кнопка
     * под ней: кнопка делала шапку выше, и при переходе на прошлый день всё прыгало.
     */
    @Test
    fun `у прошлого дня дата со стрелкой назад у сегодня — без`() {
        val day = LocalDate(2026, 11, 9)
        assertEquals("← понедельник, 9 нояб", headerDate(day, past = true))
        assertEquals("понедельник, 9 нояб", headerDate(day, past = false))
    }
}
