package n7.kcalai.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import n7.kcalai.webapi.diaryShown

/** Виден ли сейчас дневник и чем страница это замечает. */
internal class DiaryPresence(val shown: Boolean, val watch: Modifier)

/**
 * Следит за мышью и клавишами: курсор над колонкой дневника или панелью шагов — или печатают — дневник на месте,
 * иначе он задвигается (решает [diaryShown]). Заодно гасит матовое стекло под колонкой (класс `calm` в index.html).
 */
@Composable
internal fun rememberDiaryPresence(worldShown: Boolean): DiaryPresence {
    val world by rememberUpdatedState(worldShown)
    val clock = remember { TimeSource.Monotonic }
    // Только открытая страница — дневник на виду: с этого человек и начинает.
    var moved by remember { mutableStateOf(clock.markNow()) }
    var typed by remember { mutableStateOf(clock.markNow()) }
    var over by remember { mutableStateOf(true) }
    var shown by remember { mutableStateOf(true) }
    fun decide() {
        shown = diaryShown(world, over, moved.elapsedNow().inWholeMilliseconds, typed.elapsedNow().inWholeMilliseconds)
    }
    LaunchedEffect(Unit) {
        while (true) {
            decide()
            delay(TICK_MS)
        }
    }
    LaunchedEffect(shown) { setCalm(!shown) }
    val watch = Modifier
        .onPreviewKeyEvent {
            typed = clock.markNow()
            decide()
            false
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Move && event.type != PointerEventType.Press && event.type != PointerEventType.Scroll) continue
                    val at = event.changes.firstOrNull()?.position ?: continue
                    val column = abs(at.x - size.width / 2f) <= minOf(COLUMN_HALF.toPx(), size.width / 2f)
                    val panel = at.x >= size.width - PANEL_WIDTH.toPx() && at.y <= PANEL_HEIGHT.toPx()
                    over = column || panel
                    moved = clock.markNow()
                    decide()
                }
            }
        }
    return DiaryPresence(shown, watch)
}

private fun setCalm(on: Boolean): Unit = js("{ document.body.classList.toggle('calm', on); }")

private const val TICK_MS = 300L
private val COLUMN_HALF = 380.dp
private val PANEL_WIDTH = 380.dp
private val PANEL_HEIGHT = 420.dp
