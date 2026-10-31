package n7.kcalai.feature.diary

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
    fun `шапка раскрывается, когда под ней хватает места`() {
        assertTrue(headerFits(spare = 900, growth = 600))
        assertTrue(headerFits(spare = 600, growth = 600))
    }

    @Test
    fun `шапка не раскрывается, если лента под ней перестанет помещаться`() {
        assertFalse(headerFits(spare = 300, growth = 600))
    }

    @Test
    fun `лента, которая не помещается и так, шапку не раскрывает`() {
        assertFalse(headerFits(spare = null, growth = 600))
    }

    // --- Крупное число шапки ----------------------------------------------------------

    @Test
    fun `сегодня крупно — сколько ещё можно`() {
        assertEquals(Headline(519, "осталось", "1581 / 2100 ›", over = false), headline(1581, 2100, past = false))
        assertEquals(Headline(310, "перебор", "2410 / 2100 ›", over = true), headline(2410, 2100, past = false))
    }

    /**
     * Про прошедший день «осталось 519» — неправда: оставаться больше нечему.
     * Крупно стоит съеденное, а под ним — как это вышло относительно цели.
     */
    @Test
    fun `в прошлом дне крупно — сколько съедено`() {
        assertEquals(Headline(1581, "съедено", "из 2100 ›", over = false), headline(1581, 2100, past = true))
        assertEquals(Headline(2410, "съедено", "перебор 310 ›", over = true), headline(2410, 2100, past = true))
    }

    @Test
    fun `без цели — съеденное и приглашение её задать`() {
        assertEquals(Headline(900, "съедено", "задать цель ›", over = false), headline(900, null, past = false))
        assertEquals(Headline(900, "съедено", "задать цель ›", over = false), headline(900, null, past = true))
    }
}
