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
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Удалённое сообщение рассыпается на крошки, как песок: снимок пузырька режется на клетки, и они
 * волной слева направо улетают вверх-вправо, мельчая и угасая. Эффект знаком по мессенджерам,
 * реализация — своя.
 *
 * Использование: `val dust = rememberDust()`, на пузырёк — `Modifier.dust(dust)`, на «удалить» —
 * `dust.scatter()`, а убрать сообщение — когда она вернётся: к тому времени от него остался прах.
 */
class Dust internal constructor(internal val layer: GraphicsLayer) {

    internal var crumbs by mutableStateOf<List<Crumb>?>(null)
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
            crumbs = crumbsOf(pixels, image.width, image.height, cellPx, Random.Default)
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
    for (crumb in crumbs) {
        val pose = crumb.at(t)
        if (pose.alpha <= 0f) continue
        val side = dust.cell * pose.scale
        val color = Color(crumb.color)
        drawRect(color.copy(alpha = color.alpha * pose.alpha), Offset(pose.x, pose.y), Size(side, side))
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
 * Снимок → крошки: по одной на клетку [cell] x [cell], цвет — из её середины; прозрачное не рассыпается.
 * Задержка растёт слева направо (волна) с небольшим разбросом; полёт — вверх и вправо, у каждой свой.
 */
fun crumbsOf(pixels: IntArray, width: Int, height: Int, cell: Int, random: Random): List<Crumb> {
    val out = ArrayList<Crumb>()
    val step = max(1, cell)
    for (cy in 0 until height step step) {
        for (cx in 0 until width step step) {
            val color = pixels[min(height - 1, cy + step / 2) * width + min(width - 1, cx + step / 2)]
            if ((color ushr ALPHA_SHIFT) < MIN_ALPHA) continue
            val wave = if (width > 1) cx.toFloat() / width else 0f
            out += Crumb(
                x = cx.toFloat(),
                y = cy.toFloat(),
                color = color,
                delay = (wave * WAVE + random.nextFloat() * JITTER).coerceAtMost(1f - FLIGHT),
                flyX = width * (FLY_X_MIN + random.nextFloat() * FLY_X_SPREAD) + FLY_PX * random.nextFloat(),
                flyY = -height * (FLY_Y_MIN + random.nextFloat() * FLY_Y_SPREAD) - FLY_PX * random.nextFloat(),
                wobble = (random.nextFloat() - 0.5f) * height * WOBBLE,
            )
        }
    }
    return out
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
