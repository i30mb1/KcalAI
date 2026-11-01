package n7.kcalai.ui.mascot

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import n7.kcalai.model.Mascot

/**
 * Кукла персонажа.
 *
 * Бесконечная анимация однажды уже держала экран в перерисовке на частоте дисплея,
 * пока открыт дневник, — батарея и тесты интерфейса, не дожидавшиеся покоя.
 * Поэтому каждое действие обязано кончиться и вернуть персонажа в покой.
 */
class PuppetTest {

    /** Кадр раз в 16 мс виртуального времени: анимации идут без экрана. */
    private class TestFrameClock : MonotonicFrameClock {
        private var nanos = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(FRAME_MS)
            nanos += FRAME_MS * 1_000_000
            return onFrame(nanos)
        }
    }

    @Test
    fun `каждое действие конечно, короче трёх секунд и возвращает в покой`() = runTest {
        withContext(TestFrameClock()) {
            for (mascot in Mascot.entries) for (act in MascotAct.entries) {
                val puppet = MascotPuppet(mascot)
                val start = testScheduler.currentTime

                puppet.play(act)

                val took = testScheduler.currentTime - start
                assertTrue(took in 1..MAX_ACT_MS, "$mascot $act длится $took мс")
                assertTrue(puppet.isStill(), "$mascot после $act не в покое")
                assertEquals(MascotFace.CALM, puppet.face, "$mascot после $act")
                assertNull(puppet.emote, "$mascot после $act")
            }
        }
    }

    @Test
    fun `новое действие прерывает прошлое, и покой всё равно наступает`() = runTest {
        withContext(TestFrameClock()) {
            val puppet = MascotPuppet(Mascot.GRAIN)

            val dizzy = launch { puppet.play(MascotAct.DIZZY) }
            delay(300)
            puppet.play(MascotAct.HOP)
            dizzy.join()

            assertTrue(puppet.isStill())
            assertEquals(MascotFace.CALM, puppet.face)
        }
    }

    @Test
    fun `уснувший просыпается по-настоящему`() = runTest {
        withContext(TestFrameClock()) {
            val puppet = MascotPuppet(Mascot.BUTTER)

            puppet.sleep()
            assertEquals(MascotFace.ASLEEP, puppet.face)
            assertEquals(Emote.ZZZ, puppet.emote)

            puppet.play(MascotAct.SHAKE)
            assertEquals(MascotFace.ASLEEP, puppet.face, "тряска не будит: будит касание сцены")

            puppet.wake(startled = true)
            assertEquals(MascotFace.CALM, puppet.face)
            assertNull(puppet.emote)
            assertTrue(puppet.isStill())
        }
    }

    @Test
    fun `взгляд не уводит зрачки за край глаза`() {
        val looks = listOf(Offset(1f, 0f), Offset(-1f, -1f), Offset(5f, 5f), Offset(0f, -30f), Offset.Zero)

        for (look in looks) {
            val shift = eyeShift(look)
            assertTrue(hypot(shift.x, shift.y) <= MAX_EYE_SHIFT + 0.001f, "$look → $shift")
        }
        assertEquals(Offset.Zero, eyeShift(Offset.Zero))
    }

    private companion object {
        const val FRAME_MS = 16L
        const val MAX_ACT_MS = 3_000L
    }
}
