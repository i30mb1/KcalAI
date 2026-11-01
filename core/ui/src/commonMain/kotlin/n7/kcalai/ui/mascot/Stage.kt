package n7.kcalai.ui.mascot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * Правила сцены, по которым персонажи отзываются на человека: сколько раз
 * подряд их ткнули, куда смотреть, когда засыпать, где встать облачку.
 * Отдельно от рисования — их можно проверить без экрана.
 */

/** Тычок: какой по счёту подряд и не двойной ли это тап. */
data class Poke(val count: Int, val trick: Boolean)

/**
 * Счёт тычков подряд.
 *
 * Пауза дольше [windowMs] начинает счёт заново. Двойной тап — второй тычок серии,
 * пришедший быстрее [doubleTapMs]: он не ждёт, не будет ли второго, и первый тычок
 * отыгрывается сразу. Спам пальцем трюков не плодит — трюк только второй в серии.
 */
class PokeMeter(private val windowMs: Long = 4_000, private val doubleTapMs: Long = 300) {
    private var count = 0
    private var last: Long? = null

    fun poke(atMs: Long): Poke {
        val previous = last
        if (previous == null || atMs - previous > windowMs) count = 0
        val quick = previous != null && atMs - previous <= doubleTapMs
        last = atMs
        count++
        return Poke(count, trick = count == 2 && quick)
    }
}

/**
 * Куда смотреть на [to] из [from]: направление длиной не больше единицы.
 *
 * Дальше [reachPx] — взгляд до упора, ближе — вполсилы, а палец прямо на персонаже —
 * он смотрит на человека, прямо: косить глазами в собственный нос смешно один раз.
 */
fun gaze(from: Offset, to: Offset, reachPx: Float): Offset {
    val delta = to - from
    val distance = hypot(delta.x, delta.y)
    if (distance < 1f) return Offset.Zero
    val strength = (distance / reachPx).coerceAtMost(1f)
    return delta / distance * strength
}

/** Сколько осталось бодрости у оставленных без внимания. */
enum class Rest { AWAKE, DROWSY, ASLEEP }

/** Через сколько без касаний кто-то зевает. */
const val IDLE_YAWN_MS = 40_000L

/** Через сколько без касаний засыпают все. */
const val IDLE_SLEEP_MS = 60_000L

/**
 * Бодрость после [idleMs] без касаний: зевают, потом спят. Спящие замирают —
 * и экран без человека перестаёт перерисовываться.
 */
fun restAfterIdle(idleMs: Long): Rest = when {
    idleMs < IDLE_YAWN_MS -> Rest.AWAKE
    idleMs < IDLE_SLEEP_MS -> Rest.DROWSY
    else -> Rest.ASLEEP
}

/**
 * Где встать облачку.
 *
 * @param tailUp облачко под персонажем, хвостик смотрит вверх
 * @param tailX где хвостик, от левого края облачка
 */
data class SpeechPlacement(val offset: IntOffset, val tailUp: Boolean, val tailX: Int)

/** Зазор между персонажем и облачком. */
const val SPEECH_GAP_PX = 12

/** Ближе к краю облачка хвостик не подходит: там скругление. */
const val TAIL_INSET_PX = 24

/**
 * Облачко над персонажем, хвостиком к нему.
 *
 * Над ним, пока есть место; у верхнего края — под ним. По бокам — прижато
 * к краю экрана, а хвостик тянется к персонажу, насколько позволяет скругление.
 */
fun speechPlacement(
    anchor: Rect,
    bubble: IntSize,
    bounds: IntSize,
    marginPx: Int,
    gapPx: Int = SPEECH_GAP_PX,
    tailInsetPx: Int = TAIL_INSET_PX,
): SpeechPlacement {
    val centerX = anchor.center.x.roundToInt()
    val x = (centerX - bubble.width / 2).coerceIn(marginPx, (bounds.width - marginPx - bubble.width).coerceAtLeast(marginPx))
    val above = anchor.top.roundToInt() - gapPx - bubble.height
    val tailUp = above < marginPx
    val y = if (tailUp) anchor.bottom.roundToInt() + gapPx else above
    val tailX = (centerX - x).coerceIn(tailInsetPx, (bubble.width - tailInsetPx).coerceAtLeast(tailInsetPx))
    return SpeechPlacement(IntOffset(x, y), tailUp, tailX)
}
