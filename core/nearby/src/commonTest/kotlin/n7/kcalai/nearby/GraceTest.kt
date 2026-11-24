package n7.kcalai.nearby

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Сервер страницы переживает уход экрана на запас: мельком заблокировал телефон —
 * страница не заметит, убрал надолго — связь закрывается.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraceTest {

    private var starts = 0
    private var stops = 0

    private val work = flow {
        starts++
        try {
            emit(Unit)
            awaitCancellation()
        } finally {
            stops++
        }
    }

    private fun TestScope.shared(grace: Duration?) = work.shareIn(backgroundScope, graceStarted(MutableStateFlow(grace)), replay = 1)

    @Test
    fun `ушёл экран — работа держится запас и гаснет`() = runTest {
        val shared = shared(5.minutes)
        val screen = launch { shared.collect {} }
        runCurrent()
        assertEquals(1, starts)

        screen.cancel()
        advanceTimeBy(4.minutes)
        runCurrent()
        assertEquals(0, stops, "запас ещё не вышел")

        advanceTimeBy(2.minutes)
        runCurrent()
        assertEquals(1, stops)
    }

    @Test
    fun `вернулся раньше — без перезапуска`() = runTest {
        val shared = shared(5.minutes)
        val first = launch { shared.collect {} }
        runCurrent()
        first.cancel()
        advanceTimeBy(2.minutes)

        val again = launch { shared.collect {} }
        advanceTimeBy(10.minutes)
        runCurrent()
        again.cancel()

        assertEquals(1, starts, "сервер не перезапускался — страница не заметила ухода")
        assertEquals(0, stops)
    }

    @Test
    fun `пока система не остановит — не гаснет`() = runTest {
        val shared = shared(null)
        launch { shared.collect {} }.also { runCurrent() }.cancel()

        advanceTimeBy(600.minutes)
        runCurrent()

        assertEquals(0, stops)
    }
}
