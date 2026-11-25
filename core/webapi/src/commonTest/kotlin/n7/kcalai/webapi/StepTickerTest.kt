package n7.kcalai.webapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Шаги в окошке идут по одному, в такт ходьбе, а не скачками раз в секунду, когда телефон присылает итог. */
class StepTickerTest {

    @Test
    fun `между итогами шаги идут в темпе ходьбы`() {
        // 2 шага в секунду, итог только что пришёл и показан: через полсекунды — шаг сверху
        val shown = stepsShown(shown = 1_000.0, target = 1_000, sinceTargetMs = 500, stepsPerSecond = 2.0, frameMs = 500)
        assertTrue(shown in 1_000.5..1_001.5, "$shown")
    }

    @Test
    fun `отстали от итога — догоняют быстрее темпа, но не прыжком`() {
        val shown = stepsShown(shown = 1_000.0, target = 1_030, sinceTargetMs = 0, stepsPerSecond = 2.0, frameMs = 16)
        assertTrue(shown > 1_000.0 && shown < 1_005.0, "$shown")
    }

    @Test
    fun `вперёд итога не убегают дальше пары секунд ходьбы`() {
        var shown = 1_000.0
        var since = 0L
        repeat(600) {
            since += 16
            shown = stepsShown(shown, target = 1_000, sinceTargetMs = since, stepsPerSecond = 2.0, frameMs = 16)
        }
        assertTrue(shown <= 1_000 + 2.0 * STEP_LEAD_MS / 1000 + 1e-9, "$shown")
    }

    @Test
    fun `назад не отматывают — ждут, пока итог догонит`() {
        assertEquals(1_004.0, stepsShown(shown = 1_004.0, target = 1_002, sinceTargetMs = 0, stepsPerSecond = 2.0, frameMs = 16))
    }

    @Test
    fun `темп шагов — из скорости и длины шага`() {
        // 4 км/ч — 1,11 м/с, шаг человека 175 см на этой скорости ~0,70 м: ~1,6 шага в секунду
        assertTrue(stepsPerSecond(4.0) in 1.5..1.7, "${stepsPerSecond(4.0)}")
        assertEquals(0.0, stepsPerSecond(0.0))
    }
}
