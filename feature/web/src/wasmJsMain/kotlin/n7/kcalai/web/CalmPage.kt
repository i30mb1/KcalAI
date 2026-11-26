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
import n7.kcalai.webapi.diaryAfterClick

/** Виден ли сейчас дневник и чем страница это замечает. */
internal class DiaryPresence(val shown: Boolean, val watch: Modifier)

/**
 * Клик по миру убирает дневник, следующий — возвращает ([diaryAfterClick]); начали печатать — тоже возвращается.
 * Заодно гасит матовое стекло под колонкой (класс `calm` в index.html).
 */
@Composable
internal fun rememberDiaryPresence(worldShown: Boolean): DiaryPresence {
    val world by rememberUpdatedState(worldShown)
    var hidden by remember { mutableStateOf(false) }
    val shown = !worldShown || !hidden
    LaunchedEffect(shown) { setCalm(!shown) }
    val watch = Modifier
        .onPreviewKeyEvent {
            hidden = false
            false
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                // Клик, вернувший дневник, до его кнопок не доходит — ни нажатием, ни отпусканием.
                var swallow = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) {
                        val at = event.changes.firstOrNull()?.position ?: continue
                        val column = abs(at.x - size.width / 2f) <= minOf(COLUMN_HALF.toPx(), size.width / 2f)
                        val panel = at.x >= size.width - PANEL_WIDTH.toPx() && at.y <= PANEL_HEIGHT.toPx()
                        swallow = world && hidden
                        hidden = !diaryAfterClick(world, shown = !hidden, onDiary = column || panel)
                    }
                    if (swallow) event.changes.forEach { it.consume() }
                    if (event.type == PointerEventType.Release) swallow = false
                }
            }
        }
    return DiaryPresence(shown, watch)
}

private fun setCalm(on: Boolean): Unit = js("{ document.body.classList.toggle('calm', on); }")

private val COLUMN_HALF = 380.dp
private val PANEL_WIDTH = 380.dp
private val PANEL_HEIGHT = 420.dp
