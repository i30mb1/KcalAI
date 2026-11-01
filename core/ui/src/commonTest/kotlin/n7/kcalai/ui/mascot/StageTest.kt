package n7.kcalai.ui.mascot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Правила сцены: как персонажи считают тычки, куда смотрят, когда засыпают
 * и где над ними встаёт облачко.
 */
class StageTest {

    // --- Тычки ----------------------------------------------------------------------------

    @Test
    fun `тычки считаются, пока идут подряд`() {
        val meter = PokeMeter()

        assertEquals(1, meter.poke(0).count)
        assertEquals(2, meter.poke(1_000).count)
        assertEquals(3, meter.poke(2_000).count)
    }

    @Test
    fun `пауза сбрасывает счёт`() {
        val meter = PokeMeter()
        meter.poke(0)
        meter.poke(1_000)

        assertEquals(1, meter.poke(6_000).count)
    }

    @Test
    fun `двойной тап — трюк, медленный второй тык — нет`() {
        val quick = PokeMeter()
        assertFalse(quick.poke(0).trick)
        assertTrue(quick.poke(200).trick)

        val slow = PokeMeter()
        slow.poke(0)
        assertFalse(slow.poke(800).trick)
    }

    /** Спам пальцем — не повод для трюка на каждом тапе: трюк только второй в серии. */
    @Test
    fun `сто тапов подряд не ломают счёт`() {
        val meter = PokeMeter()

        val pokes = (0 until 100).map { meter.poke(it * 100L) }

        assertEquals((1..100).toList(), pokes.map { it.count })
        assertEquals(listOf(2), pokes.filter { it.trick }.map { it.count })
    }

    // --- Взгляд ---------------------------------------------------------------------------

    @Test
    fun `далёкий палец — взгляд до упора в его сторону`() {
        val look = gaze(from = Offset(100f, 100f), to = Offset(900f, 100f), reachPx = 300f)

        assertEquals(1f, look.x, 0.001f)
        assertEquals(0f, look.y, 0.001f)
    }

    @Test
    fun `близкий палец — взгляд вполсилы, палец на персонаже — прямо`() {
        val near = gaze(from = Offset(100f, 100f), to = Offset(100f, 250f), reachPx = 300f)
        assertEquals(0.5f, near.y, 0.001f)

        assertEquals(Offset.Zero, gaze(from = Offset(100f, 100f), to = Offset(100.4f, 100f), reachPx = 300f))
    }

    @Test
    fun `взгляд никогда не длиннее единицы`() {
        val targets = listOf(Offset(-5_000f, 3_000f), Offset(0f, 0f), Offset(220f, 99f), Offset(101f, 101f))

        targets.forEach { target ->
            val look = gaze(from = Offset(100f, 100f), to = target, reachPx = 300f)
            assertTrue(hypot(look.x, look.y) <= 1.0001f, "$target → $look")
        }
    }

    // --- Сон ------------------------------------------------------------------------------

    @Test
    fun `от безделья сначала зевают, потом спят`() {
        assertEquals(Rest.AWAKE, restAfterIdle(10_000))
        assertEquals(Rest.DROWSY, restAfterIdle(45_000))
        assertEquals(Rest.ASLEEP, restAfterIdle(70_000))
    }

    // --- Облачко --------------------------------------------------------------------------

    private val screen = IntSize(1_080, 2_000)
    private val bubble = IntSize(400, 120)

    @Test
    fun `облачко — над персонажем, хвостиком к нему`() {
        val mascot = Rect(left = 400f, top = 800f, right = 520f, bottom = 900f)

        val placed = speechPlacement(mascot, bubble, screen, marginPx = 32)

        assertEquals(IntOffset(260, 800 - 120 - SPEECH_GAP_PX), placed.offset)
        assertFalse(placed.tailUp)
        assertEquals(200, placed.tailX)
    }

    @Test
    fun `у верхнего края — под персонажем`() {
        val mascot = Rect(left = 400f, top = 60f, right = 520f, bottom = 160f)

        val placed = speechPlacement(mascot, bubble, screen, marginPx = 32)

        assertTrue(placed.tailUp)
        assertEquals(160 + SPEECH_GAP_PX, placed.offset.y)
    }

    @Test
    fun `у бокового края — прижато к краю, а хвостик всё равно у персонажа`() {
        val mascot = Rect(left = 0f, top = 800f, right = 60f, bottom = 900f)

        val placed = speechPlacement(mascot, bubble, screen, marginPx = 32)

        assertEquals(32, placed.offset.x)
        assertEquals(TAIL_INSET_PX, placed.tailX, "хвостик сдвинут к персонажу до упора")
    }
}
