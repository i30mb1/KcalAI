package n7.kcalai.ui

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Удалённое сообщение рассыпается на крошки: волной слева направо, крошки улетают и гаснут. */
class DustTest {

    private val opaque = 0xFF336699.toInt()

    /** Картинка 8 x 4: левая половина закрашена, правая прозрачна. */
    private val half = IntArray(8 * 4) { i -> if (i % 8 < 4) opaque else 0 }

    @Test
    fun `крошки — только из закрашенного, по клетке на крошку`() {
        val crumbs = crumbsOf(half, width = 8, height = 4, cell = 2, random = Random(1))
        assertEquals(4, crumbs.size, "левая половина 4 x 4 при клетке 2 — четыре крошки")
        assertTrue(crumbs.all { it.x < 4f && it.color == opaque })
    }

    @Test
    fun `в начале крошки на своих местах и видны, в конце погасли`() {
        val crumb = crumbsOf(half, 8, 4, 2, Random(1)).first()
        val start = crumb.at(0f)
        assertEquals(crumb.x, start.x)
        assertEquals(crumb.y, start.y)
        assertEquals(1f, start.alpha)
        assertEquals(0f, crumb.at(1f).alpha)
    }

    @Test
    fun `волна идёт слева направо — правые крошки трогаются позже`() {
        val wide = IntArray(40 * 2) { opaque }
        val crumbs = crumbsOf(wide, width = 40, height = 2, cell = 2, random = Random(3))
        val left = crumbs.filter { it.x < 10f }.maxOf { it.delay }
        val right = crumbs.filter { it.x >= 30f }.minOf { it.delay }
        assertTrue(left < right, "левые до $left, правые от $right")
    }

    @Test
    fun `крошки летят вверх и вправо`() {
        val crumbs = crumbsOf(IntArray(20 * 20) { opaque }, 20, 20, 2, Random(5))
        crumbs.forEach { c ->
            val mid = c.at(c.delay + 0.3f)
            assertTrue(mid.x > c.x && mid.y < c.y, "крошка $c в $mid")
        }
    }
}
