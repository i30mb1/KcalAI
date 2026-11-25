package n7.kcalai.feature.diary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Переписку листают пальцем: вправо — на день раньше, влево — на день позже, как страницы.
 * Дни — те же семь, что на графике недели.
 */
class DaySwipeTest {

    private val today = 20_782L
    private val width = 1_000f

    private fun target(shown: Long, drag: Float, velocity: Float = 0f) = swipedDay(shown, today, drag, width, velocity)

    @Test
    fun `увели далеко вправо — день раньше, влево — позже`() {
        assertEquals(today - 1, target(today, drag = 300f))
        assertEquals(today - 2, target(today - 3, drag = -300f))
    }

    @Test
    fun `чуть сдвинули и отпустили — тот же день`() {
        assertNull(target(today - 1, drag = 100f))
        assertNull(target(today - 1, drag = -100f))
    }

    @Test
    fun `быстрый взмах листает и с малого сдвига`() {
        assertEquals(today - 1, target(today, drag = 60f, velocity = 2_000f))
        assertNull(target(today - 1, drag = 60f, velocity = -2_000f), "взмах назад — передумали")
    }

    @Test
    fun `в будущее и дальше недели не листается`() {
        assertNull(target(today, drag = -500f))
        assertNull(target(today - 6, drag = 500f))
        assertEquals(today - 6, target(today - 5, drag = 500f))
    }
}
