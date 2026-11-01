package n7.kcalai.ui.mascot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Что вылетает из персонажа: «з-з-з» во сне, сердечко, искры, капля, звёздочки.
 *
 * Одна за раз и конечная: прогресс идёт от нуля к единице и останавливается.
 * Сонные «з» на единице замирают, а не исчезают: спящий виден и на неподвижном экране.
 */
enum class Emote { ZZZ, HEART, SPARKLES, DROP, STARS }

private val Heart = Color(0xFFE58BA8)
private val Spark = Color(0xFFF0C077)
private val Water = Color(0xFF9CC9E8)

internal fun DrawScope.emote(emote: Emote, progress: Float) {
    when (emote) {
        Emote.ZZZ -> zzz(progress)
        Emote.HEART -> heart(progress)
        Emote.SPARKLES -> sparkles(progress)
        Emote.DROP -> drop(progress)
        Emote.STARS -> stars(progress)
    }
}

/** Три «з» уплывают вверх вправо; на единице прогресса стоят лесенкой. */
private fun DrawScope.zzz(progress: Float) {
    for (index in 0 until 3) {
        val phase = (progress * 2f + index / 3f) % 1f
        val size = 4f + phase * 5f
        val origin = Offset(86f + phase * 20f, 28f - phase * 32f)
        val path = Path().apply {
            moveTo(origin.x, origin.y)
            lineTo(origin.x + size, origin.y)
            lineTo(origin.x, origin.y + size)
            lineTo(origin.x + size, origin.y + size)
        }
        drawPath(path, Ink.copy(alpha = 1f - phase * 0.55f), style = Stroke(width = 1.6f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.heart(progress: Float) {
    val alpha = (1f - progress).coerceIn(0f, 1f)
    translate(60f, 8f - progress * 22f) {
        scale(0.7f + progress * 0.5f, pivot = Offset.Zero) {
            val path = Path().apply {
                moveTo(0f, 5f)
                cubicTo(-9f, -1f, -5f, -9f, 0f, -4f)
                cubicTo(5f, -9f, 9f, -1f, 0f, 5f)
                close()
            }
            drawPath(path, Heart.copy(alpha = alpha))
        }
    }
}

/** Четыре искры разлетаются по диагоналям. */
private fun DrawScope.sparkles(progress: Float) {
    val alpha = (1f - progress).coerceIn(0f, 1f)
    for (index in 0 until 4) {
        val angle = (PI / 4 + index * PI / 2).toFloat()
        val radius = 46f + progress * 14f
        val center = Offset(60f + cos(angle) * radius, 52f + sin(angle) * radius * 0.8f)
        val arm = 3.5f
        drawLine(Spark.copy(alpha = alpha), center - Offset(arm, 0f), center + Offset(arm, 0f), 2f, StrokeCap.Round)
        drawLine(Spark.copy(alpha = alpha), center - Offset(0f, arm), center + Offset(0f, arm), 2f, StrokeCap.Round)
    }
}

/** Капля у виска: страшно, высоко. */
private fun DrawScope.drop(progress: Float) {
    translate(88f + progress * 2f, 46f + progress * 6f) {
        val path = Path().apply {
            moveTo(0f, -5f)
            quadraticTo(4f, 1f, 0f, 4f)
            quadraticTo(-4f, 1f, 0f, -5f)
            close()
        }
        drawPath(path, Water)
        drawPath(path, Ink.copy(alpha = 0.5f), style = Stroke(width = 0.8f))
    }
}

/** Звёздочки кружат над головой, к концу гаснут. */
private fun DrawScope.stars(progress: Float) {
    val alpha = if (progress < 0.85f) 1f else ((1f - progress) / 0.15f).coerceIn(0f, 1f)
    for (index in 0 until 3) {
        val angle = (progress * 4 * PI + index * 2 * PI / 3).toFloat()
        val center = Offset(60f + cos(angle) * 18f, 4f + sin(angle) * 5f)
        drawLine(Spark.copy(alpha = alpha), center - Offset(2.5f, 0f), center + Offset(2.5f, 0f), 1.8f, StrokeCap.Round)
        drawLine(Spark.copy(alpha = alpha), center - Offset(0f, 2.5f), center + Offset(0f, 2.5f), 1.8f, StrokeCap.Round)
    }
}
