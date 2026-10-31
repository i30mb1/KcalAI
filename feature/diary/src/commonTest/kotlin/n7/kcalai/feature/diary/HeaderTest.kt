package n7.kcalai.feature.diary

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
}
