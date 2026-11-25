package n7.kcalai.feature.diary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.launch

/**
 * Какой день открыть, отпустив переписку: увели вправо — на день раньше, влево — позже, как страницы.
 * Листает дальний увод или быстрый взмах в ту же сторону; взмах назад — передумали. Дни — те же семь,
 * что на графике недели.
 *
 * @param drag на сколько увели переписку, px; [velocity] — с какой скоростью отпустили, px/с
 * @return день; `null` — остаться на открытом
 */
internal fun swipedDay(shown: Long, today: Long, drag: Float, width: Float, velocity: Float): Long? {
    val flung = abs(velocity) > FLING_PX_PER_S
    if (flung && sign(velocity) != sign(drag)) return null
    if (!flung && abs(drag) < width * SWIPE_SHARE) return null
    val next = if (drag > 0) shown - 1 else shown + 1
    return next.takeIf { it in today - (WEEK_DAYS - 1)..today }
}

/**
 * Переписка дня, которую листают пальцем: идёт за пальцем, уезжает и следующая въезжает с другой стороны.
 * День, выбранный тапом по неделе или «назад», въезжает с той стороны, где он на неделе, — переписка
 * не подменяется рывком. Вертикальная прокрутка ленты жесту не мешает.
 *
 * Сдвиг — раскладкой, а не слоем: персонажи у реплик едут вместе с ними.
 */
@Composable
internal fun DayPages(
    date: Long,
    today: Long,
    onSelectDay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    var shown by remember { mutableLongStateOf(date) }
    val scope = rememberCoroutineScope()
    val day by rememberUpdatedState(date)

    LaunchedEffect(date) {
        if (date == shown) return@LaunchedEffect
        // День раньше въезжает слева. После свайпа прежний уже за краем — новый идёт во всю ширину,
        // после тапа — с короткого подхода, чтобы не гонять экран через весь телефон.
        val side = if (date < shown) -1f else 1f
        val from = if (abs(offset.value) >= width * GONE_SHARE) width.toFloat() else width * ENTER_SHARE
        offset.snapTo(side * from)
        shown = date
        offset.animateTo(0f, tween(ENTER_MS, easing = LinearOutSlowInEasing))
    }

    Box(
        modifier
            .onSizeChanged { width = it.width }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    // Там, куда листать некуда, переписка поддаётся туго — и вернётся.
                    val moved = offset.value + delta
                    val closed = (moved < 0 && day >= today) || (moved > 0 && day <= today - (WEEK_DAYS - 1))
                    scope.launch { offset.snapTo(offset.value + if (closed) delta * RESISTANCE else delta) }
                },
                onDragStopped = { velocity ->
                    val next = swipedDay(day, today, offset.value, width.toFloat(), velocity)
                    if (next == null) {
                        offset.animateTo(0f, spring(), initialVelocity = velocity)
                    } else {
                        offset.animateTo(sign(offset.value) * width, tween(EXIT_MS, easing = FastOutLinearInEasing))
                        onSelectDay(next)
                    }
                },
            ),
    ) {
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .graphicsLayer {
                    // Новый день уже скомпонован, а на край ещё не отведён — этот кадр не показываем.
                    alpha = if (date != shown) 0f else 1f - FADE * (abs(offset.value) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
                },
        ) {
            content()
        }
    }
}

private const val WEEK_DAYS = 7
private const val SWIPE_SHARE = 0.25f
private const val FLING_PX_PER_S = 1_000f
private const val RESISTANCE = 0.25f
private const val ENTER_SHARE = 0.3f
private const val GONE_SHARE = 0.9f
private const val FADE = 0.9f
private const val EXIT_MS = 140
private const val ENTER_MS = 260
