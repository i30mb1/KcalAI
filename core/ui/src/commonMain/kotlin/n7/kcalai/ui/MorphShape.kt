package n7.kcalai.ui

import androidx.collection.FloatFloatPair
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import kotlin.math.max
import kotlin.math.min

/**
 * Кадр перехода [morph] на шаге [progress] как форма Compose.
 *
 * Шаг меняется каждый кадр, поэтому форма создаётся заново в блоке
 * `graphicsLayer { shape = MorphShape(...) }`: значение читается при отрисовке
 * слоя, и анимация не пересобирает композицию.
 */
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        morph.fitted(progress, size).forEachIndexed { index, cubic ->
            if (index == 0) path.moveTo(cubic.anchor0X, cubic.anchor0Y)
            path.cubicTo(cubic.control0X, cubic.control0Y, cubic.control1X, cubic.control1Y, cubic.anchor1X, cubic.anchor1Y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/**
 * Кривые кадра, вписанные в [size].
 *
 * Масштаб берётся по наибольшим границам всего перехода, а не текущего кадра:
 * иначе каждый кадр растягивался бы во всю кнопку, и форма не росла и не
 * сжималась, а только меняла очертания. Кадр за пределами перехода — перелёт
 * пружины — вписывается вместе с ним, чтобы лепесток не обрезался краем кнопки.
 *
 * Геометрия считается по кривым, а не по [Path]: так её можно проверить
 * обычным тестом, без устройства.
 */
internal fun Morph.fitted(progress: Float, size: Size): List<Cubic> {
    val cubics = asCubics(progress)
    val whole = calculateMaxBounds()
    var left = whole[0]
    var top = whole[1]
    var right = whole[2]
    var bottom = whole[3]
    cubics.forEachPoint { x, y ->
        left = min(left, x)
        top = min(top, y)
        right = max(right, x)
        bottom = max(bottom, y)
    }
    val scale = min(size.width, size.height) / max(right - left, bottom - top)
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    return cubics.map { cubic ->
        cubic.transformed { x, y ->
            FloatFloatPair((x - centerX) * scale + size.width / 2f, (y - centerY) * scale + size.height / 2f)
        }
    }
}

/** Точки кривых с шагом, частым достаточно, чтобы границы совпали с отрисованными до доли точки. */
internal inline fun List<Cubic>.forEachPoint(action: (x: Float, y: Float) -> Unit) {
    for (cubic in this) {
        for (step in 0..CURVE_STEPS) {
            val t = step.toFloat() / CURVE_STEPS
            val u = 1f - t
            val a = u * u * u
            val b = 3f * u * u * t
            val c = 3f * u * t * t
            val d = t * t * t
            action(
                a * cubic.anchor0X + b * cubic.control0X + c * cubic.control1X + d * cubic.anchor1X,
                a * cubic.anchor0Y + b * cubic.control0Y + c * cubic.control1Y + d * cubic.anchor1Y,
            )
        }
    }
}

private const val CURVE_STEPS = 16
