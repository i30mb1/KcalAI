package n7.kcalai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Удалённое сообщение рассыпается на крошки, как песок: снимок пузырька режется на клетки, и они
 * волной слева направо улетают вверх-вправо, мельчая и угасая. Куда волна ещё не дошла, там остаётся
 * сам снимок, а не мозаика: текст цел до последнего мгновения. Эффект знаком по мессенджерам,
 * реализация — своя.
 *
 * Использование: `val dust = rememberDust()`, на пузырёк — `Modifier.dust(dust)`, на «удалить» —
 * `dust.scatter()`, а убрать сообщение — когда она вернётся: к тому времени от него остался прах.
 */
class Dust internal constructor(internal val layer: GraphicsLayer) {

    internal var crumbs by mutableStateOf<List<Crumb>?>(null)
        private set
    /** Снимок пузырька — им рисуется нетронутое волной. */
    internal var image: ImageBitmap? = null
        private set
    /** Крошки по строкам, слева направо: нетронутый хвост строки рисуется снимком. */
    internal var rows: List<List<Crumb>> = emptyList()
        private set
    internal var cell = 1f
        private set
    internal val time = Animatable(0f)

    /** Рассыпать и дождаться, пока последняя крошка погаснет. Повторный вызов ждёт ту же анимацию. */
    suspend fun scatter(cellPx: Int = DEFAULT_CELL_PX) {
        if (crumbs == null) {
            val image = layer.toImageBitmap()
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            cell = cellPx.toFloat()
            val all = crumbsOf(pixels, image.width, image.height, cellPx, Random.Default)
            this.image = image
            rows = all.groupBy { it.y }.values.map { row -> row.sortedBy { it.x } }
            crumbs = all
        }
        time.animateTo(1f, tween(DUST_MS, easing = LinearEasing))
    }
}

@Composable
fun rememberDust(): Dust {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Dust(layer) }
}

/**
 * Сообщение с корзинкой: корзинка сначала рассыпает его на крошки, и только потом [onHide] убирает
 * его из переписки — соседи съезжают на место праха. Без корзинки — содержимое как есть.
 *
 * @param content получает корзинку: `null` — её нет
 */
@Composable
fun Crumbling(onHide: (() -> Unit)?, content: @Composable (hide: (() -> Unit)?) -> Unit) {
    if (onHide == null) return content(null)
    val dust = rememberDust()
    val scope = rememberCoroutineScope()
    val cell = with(LocalDensity.current) { CRUMB.roundToPx() }
    Box(Modifier.dust(dust)) {
        content {
            scope.launch {
                dust.scatter(cell)
                onHide()
            }
        }
    }
}

/** Крошка — пара точек экрана: мельче — пыль без формы, крупнее — мозаика. */
private val CRUMB = 2.dp

/** Пока не рассыпали — рисует содержимое как есть (и снимает его на будущее), потом — крошки. */
fun Modifier.dust(dust: Dust): Modifier = drawWithContent {
    val crumbs = dust.crumbs
    if (crumbs == null) {
        dust.layer.record { this@drawWithContent.drawContent() }
        drawLayer(dust.layer)
        return@drawWithContent
    }
    val t = dust.time.value
    val image = dust.image
    val side = dust.cell.toInt()
    if (image != null) {
        // нетронутый хвост каждой строки — снимком, одним куском на строку
        for (row in dust.rows) {
            val front = restFront(row, t, image.width)
            if (front >= image.width) continue
            val y = row.first().y.toInt()
            val size = IntSize(image.width - front, min(side, image.height - y))
            drawImage(image, srcOffset = IntOffset(front, y), srcSize = size, dstOffset = IntOffset(front, y), dstSize = size)
        }
    }
    for (crumb in crumbs) {
        if (t < crumb.delay) continue // ещё на месте — нарисована снимком
        val pose = crumb.at(t)
        if (pose.alpha <= 0f) continue
        val size = dust.cell * pose.scale
        val color = Color(crumb.color)
        drawRect(color.copy(alpha = color.alpha * pose.alpha), Offset(pose.x, pose.y), Size(size, size))
    }
}

/** Крошка: где была в снимке (px), её цвет (ARGB), когда трогается и куда летит за весь путь. */
data class Crumb(val x: Float, val y: Float, val color: Int, val delay: Float, val flyX: Float, val flyY: Float, val wobble: Float)

/** Где крошка в момент анимации, насколько видна и какого размера (доля клетки). */
data class CrumbPose(val x: Float, val y: Float, val alpha: Float, val scale: Float)

/** Поза крошки в момент [t] — доля всей анимации, 0…1. */
fun Crumb.at(t: Float): CrumbPose {
    val p = ((t - delay) / FLIGHT).coerceIn(0f, 1f)
    // разгон: сначала крошка отрывается нехотя, потом уносится
    val e = p * p * (FLY_EASE - (FLY_EASE - 1f) * p)
    return CrumbPose(
        x = x + flyX * e + wobble * p * (1f - p),
        y = y + flyY * e,
        alpha = if (p >= 1f) 0f else 1f - p * p,
        scale = 1f - SHRINK * p,
    )
}

/**
 * Снимок → крошки: по одной на клетку [cell] x [cell], цвет — средний по клетке; прозрачное не рассыпается.
 * Задержка растёт слева направо (волна); разброс — у строки целиком, поэтому внутри строки крошки трогаются
 * строго по порядку и нетронутое — её хвост. Полёт — вверх и вправо, у каждой свой.
 */
fun crumbsOf(pixels: IntArray, width: Int, height: Int, cell: Int, random: Random): List<Crumb> {
    val out = ArrayList<Crumb>()
    val step = max(1, cell)
    for (cy in 0 until height step step) {
        val rowJitter = random.nextFloat() * JITTER
        for (cx in 0 until width step step) {
            val color = average(pixels, width, cx, cy, min(step, width - cx), min(step, height - cy))
            if ((color ushr ALPHA_SHIFT) < MIN_ALPHA) continue
            val wave = if (width > 1) cx.toFloat() / width else 0f
            out += Crumb(
                x = cx.toFloat(),
                y = cy.toFloat(),
                color = color,
                delay = (wave * WAVE + rowJitter).coerceAtMost(1f - FLIGHT),
                flyX = width * (FLY_X_MIN + random.nextFloat() * FLY_X_SPREAD) + FLY_PX * random.nextFloat(),
                flyY = -height * (FLY_Y_MIN + random.nextFloat() * FLY_Y_SPREAD) - FLY_PX * random.nextFloat(),
                wobble = (random.nextFloat() - 0.5f) * height * WOBBLE,
            )
        }
    }
    return out
}

/**
 * С какого пикселя строка ещё нетронута волной в момент [t]: первая крошка, что не тронулась; все тронулись — [width].
 * Крошки строки — слева направо, и трогаются они по порядку (см. [crumbsOf]).
 */
fun restFront(row: List<Crumb>, t: Float, width: Int): Int {
    var lo = 0
    var hi = row.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (row[mid].delay > t) hi = mid else lo = mid + 1
    }
    return row.getOrNull(lo)?.x?.toInt() ?: width
}

/** Средний цвет клетки (ARGB, с учётом прозрачности): крошка из одной точки ломает тонкие буквы в кашу. */
private fun average(pixels: IntArray, width: Int, x: Int, y: Int, w: Int, h: Int): Int {
    var a = 0L
    var r = 0L
    var g = 0L
    var b = 0L
    for (yy in y until y + h) for (xx in x until x + w) {
        val c = pixels[yy * width + xx]
        val ca = (c ushr ALPHA_SHIFT).toLong()
        a += ca
        r += ((c shr 16) and 0xFF) * ca
        g += ((c shr 8) and 0xFF) * ca
        b += (c and 0xFF) * ca
    }
    val n = (w * h).toLong()
    if (a == 0L) return 0
    return ((a / n).toInt() shl ALPHA_SHIFT) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
}

/** Вся анимация: волна проходит пузырёк, последняя крошка гаснет. */
const val DUST_MS = 900
private const val DEFAULT_CELL_PX = 6
private const val FLIGHT = 0.55f
private const val WAVE = 0.38f
private const val JITTER = 0.07f
private const val FLY_X_MIN = 0.12f
private const val FLY_X_SPREAD = 0.22f
private const val FLY_Y_MIN = 0.35f
private const val FLY_Y_SPREAD = 0.6f
private const val FLY_PX = 40f
private const val WOBBLE = 0.5f
private const val SHRINK = 0.6f
private const val FLY_EASE = 1.8f
private const val ALPHA_SHIFT = 24
private const val MIN_ALPHA = 40
