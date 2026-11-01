package n7.kcalai.shake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Тряска телефона — у персонажей кружится голова.
 *
 * Отличить тряску от обычной жизни телефона: его кладут на стол, достают
 * из кармана, роняют на диван. Один рывок — не тряска; три подряд за полсекунды — она.
 */
class ShakeDetectorTest {

    private val still = floatArrayOf(0f, 0f, 9.81f)
    private val jolt = floatArrayOf(14f, 18f, 9.81f)

    private fun ShakeDetector.feed(sample: FloatArray, atMs: Long) = onSample(sample[0], sample[1], sample[2], atMs)

    @Test
    fun `спокойно лежит — не тряска`() {
        val detector = ShakeDetector()

        val shakes = (0 until 200).count { detector.feed(still, it * 20L) }

        assertEquals(0, shakes)
    }

    @Test
    fun `три рывка подряд — тряска`() {
        val detector = ShakeDetector()

        assertFalse(detector.feed(jolt, 0))
        assertFalse(detector.feed(jolt, 150))
        assertEquals(true, detector.feed(jolt, 300))
    }

    /** Достали из кармана, положили на стол — рывки есть, но редкие. */
    @Test
    fun `редкие рывки — не тряска`() {
        val detector = ShakeDetector()

        val shakes = listOf(0L, 700L, 1_400L, 2_100L).count { detector.feed(jolt, it) }

        assertEquals(0, shakes)
    }

    /** Трясут долго — а голова кружится раз, потом передышка. */
    @Test
    fun `после тряски полторы секунды тишины`() {
        val detector = ShakeDetector()
        listOf(0L, 150L, 300L).forEach { detector.feed(jolt, it) }

        val during = listOf(400L, 500L, 600L, 700L).count { detector.feed(jolt, it) }
        val after = listOf(2_000L, 2_150L, 2_300L).count { detector.feed(jolt, it) }

        assertEquals(0, during)
        assertEquals(1, after)
    }
}
