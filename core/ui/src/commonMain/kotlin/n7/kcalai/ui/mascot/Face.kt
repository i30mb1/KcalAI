package n7.kcalai.ui.mascot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Выражение лица.
 *
 * Лицо ручкой поверх бумаги — поэтому меняется только оно: вырезку не перерисуешь,
 * а глаза и рот — штрихи, которые персонаж «перечёркивает» на ходу.
 */
enum class MascotFace {
    /** Как его знают: точки-глаза и свой рот. */
    CALM,

    /** Глаза дугами вверх, рот шире. */
    HAPPY,

    /** Глаза больше, рот буквой «о». */
    SURPRISED,

    /** Глаза закрыты, рот чуть приоткрыт. */
    ASLEEP,

    /** Спирали вместо глаз. */
    DIZZY,

    /** Счастье с румянцем: гладят. */
    BLISS,

    /** Глаза прищурены, рот прямой. */
    GRUMPY,

    /** Глаза закрыты, рот большим «О». */
    YAWN,

    /** Рот дугой вниз. */
    WORRIED,
}

/**
 * Где на рисунке лицо. У каждого своё: рисунки разные, а выражения — общие.
 *
 * @param mouth рот в покое — тот, с которым персонаж узнаётся
 */
internal class FaceGeometry(
    val leftEye: Offset,
    val rightEye: Offset,
    val mouth: Path,
    val mouthCenter: Offset,
    val mouthWidth: Float,
    val leftCheek: Offset,
    val rightCheek: Offset,
)

internal val Ink = Color(0xFF2C2618)
private val Blush = Color(0xFFF3B5C8)
private const val EYE_RADIUS = 5.5f
private const val PEN = 2f

/** Насколько далеко зрачки уходят в сторону взгляда, в единицах холста. */
internal const val MAX_EYE_SHIFT = 2.5f

/**
 * Сдвиг глаз к цели взгляда.
 *
 * Глаза — точки без белков, поэтому «смотрит» вся точка. Длинный вектор
 * обрезается: глаз не выходит за лицо, как далеко ни был бы палец.
 */
internal fun eyeShift(look: Offset): Offset {
    val length = hypot(look.x, look.y)
    if (length == 0f) return Offset.Zero
    val scale = MAX_EYE_SHIFT / maxOf(length, 1f)
    return Offset(look.x * scale, look.y * scale)
}

/**
 * Лицо ручкой поверх бумаги, без наклона тела: лицо смотрит прямо.
 *
 * Рот и щёки идут за глазами вполовину: плоская вырезка так «поворачивает голову».
 *
 * @param look куда смотрит, компоненты −1…1
 * @param blink 0 — открыты, 1 — закрыты
 */
internal fun DrawScope.face(geometry: FaceGeometry, expression: MascotFace, look: Offset, blink: Float) {
    val eyes = eyeShift(look)
    val rest = eyes * 0.5f
    if (expression == MascotFace.BLISS) {
        drawCircle(Blush, 5.5f, geometry.leftCheek + rest)
        drawCircle(Blush, 5.5f, geometry.rightCheek + rest)
    }
    for (center in listOf(geometry.leftEye, geometry.rightEye)) eye(center + eyes, expression, blink)
    mouth(geometry, expression, rest)
}

private fun DrawScope.eye(center: Offset, expression: MascotFace, blink: Float) {
    when (expression) {
        MascotFace.CALM, MascotFace.WORRIED -> dot(center, EYE_RADIUS, blink)
        MascotFace.SURPRISED -> dot(center, EYE_RADIUS * 1.2f, blink)
        MascotFace.GRUMPY -> dot(center, EYE_RADIUS, maxOf(blink, 0.5f))
        MascotFace.HAPPY, MascotFace.BLISS -> arc(center, up = true)
        MascotFace.ASLEEP, MascotFace.YAWN -> arc(center, up = false)
        MascotFace.DIZZY -> spiral(center)
    }
}

/** Точка-глаз. Моргание сплющивает её по вертикали: закрытый глаз — чёрточка. */
private fun DrawScope.dot(center: Offset, radius: Float, blink: Float) {
    val half = radius * (1f - 0.85f * blink)
    drawOval(Ink, topLeft = Offset(center.x - radius, center.y - half), size = Size(radius * 2, half * 2))
}

/** «^» — радость, «◡» — закрытый глаз. */
private fun DrawScope.arc(center: Offset, up: Boolean) {
    val path = Path().apply {
        if (up) {
            moveTo(center.x - 4.6f, center.y + 1.6f)
            quadraticTo(center.x, center.y - 4.4f, center.x + 4.6f, center.y + 1.6f)
        } else {
            moveTo(center.x - 4.6f, center.y - 0.6f)
            quadraticTo(center.x, center.y + 4.4f, center.x + 4.6f, center.y - 0.6f)
        }
    }
    drawPath(path, Ink, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
}

/** Полтора витка спирали — голова кружится. */
private fun DrawScope.spiral(center: Offset) {
    val path = Path()
    for (step in 0..SPIRAL_STEPS) {
        val t = step.toFloat() / SPIRAL_STEPS
        val angle = t * 3f * PI.toFloat()
        val radius = 0.6f + t * 4.6f
        val point = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
        if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    drawPath(path, Ink, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
}

private const val SPIRAL_STEPS = 24

private fun DrawScope.mouth(geometry: FaceGeometry, expression: MascotFace, shift: Offset) {
    val center = geometry.mouthCenter + shift
    val width = geometry.mouthWidth
    when (expression) {
        MascotFace.CALM -> translate(shift.x, shift.y) {
            drawPath(geometry.mouth, Ink, style = Stroke(width = PEN, cap = StrokeCap.Round))
        }
        MascotFace.HAPPY, MascotFace.BLISS -> arcMouth(center, width + 3f, depth = 5.5f)
        MascotFace.WORRIED -> arcMouth(center + Offset(0f, 2.5f), width, depth = -4f)
        MascotFace.SURPRISED -> drawCircle(Ink, 2.8f, center, style = Stroke(width = PEN))
        MascotFace.ASLEEP -> drawCircle(Ink, 1.4f, center)
        MascotFace.YAWN -> drawOval(Ink, topLeft = center - Offset(4.2f, 4.6f), size = Size(8.4f, 10.2f))
        MascotFace.GRUMPY -> drawLine(
            Ink,
            center + Offset(-width / 2, -0.8f),
            center + Offset(width / 2, 0.8f),
            strokeWidth = PEN,
            cap = StrokeCap.Round,
        )
        MascotFace.DIZZY -> {
            val path = Path().apply {
                moveTo(center.x - width / 2, center.y)
                quadraticTo(center.x - width / 4, center.y - 2.5f, center.x, center.y)
                quadraticTo(center.x + width / 4, center.y + 2.5f, center.x + width / 2, center.y)
            }
            drawPath(path, Ink, style = Stroke(width = PEN, cap = StrokeCap.Round))
        }
    }
}

/** Дуга-рот: [depth] вниз — улыбка, вверх — грусть. */
private fun DrawScope.arcMouth(center: Offset, width: Float, depth: Float) {
    val path = Path().apply {
        moveTo(center.x - width / 2, center.y - depth / 3)
        quadraticTo(center.x, center.y + depth, center.x + width / 2, center.y - depth / 3)
    }
    drawPath(path, Ink, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
}
