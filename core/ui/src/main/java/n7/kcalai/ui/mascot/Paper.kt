package n7.kcalai.ui.mascot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser

/**
 * Общий для троих материал: бумажные вырезки на крафте и лицо ручкой.
 *
 * Здесь только то, из чего персонажи сделаны. Как выглядит каждый — в его папке.
 */

/** Контур из макета: `d` атрибута SVG-пути, как есть. */
internal fun svgPath(d: String): Path = PathParser().parsePathString(d).toPath()

private val Ink = Color(0xFF2C2618)
private val PaperShadow = Color(0x593B2A12)
private val ShadowOffset = Offset(0.6f, 1.8f)

/**
 * Вырезка из бумаги: та же форма, сдвинутая вниз тенью, и поверх — сама.
 * На 24 dp тень почти не видна, и это нормально: там персонаж держится силуэтом.
 */
internal fun DrawScope.paper(path: Path, color: Color) {
    translate(ShadowOffset.x, ShadowOffset.y) { drawPath(path, PaperShadow) }
    drawPath(path, color)
}

/** Бумажная полоска — завиток, хохолок. */
internal fun DrawScope.paperStroke(path: Path, color: Color, width: Float) {
    val stroke = Stroke(width = width, cap = StrokeCap.Round)
    translate(ShadowOffset.x, ShadowOffset.y) { drawPath(path, PaperShadow, style = stroke) }
    drawPath(path, color, style = stroke)
}

/** Бумажный кружок — румянец. */
internal fun DrawScope.paperDot(center: Offset, radius: Float, color: Color) {
    drawCircle(PaperShadow, radius, center + ShadowOffset)
    drawCircle(color, radius, center)
}

/**
 * Ручки позы — за телом, поэтому рисуются первыми.
 *
 * Геометрия общая для троих: плечи у всех на одной высоте холста.
 * Свой у каждого только цвет.
 */
internal fun DrawScope.arms(pose: MascotPose, skin: Color, outline: Color, waveDegrees: Float) {
    when (pose) {
        MascotPose.HOME, MascotPose.PEEK -> Unit
        MascotPose.LEAN -> {
            arm(leanReach, skin, outline)
            arm(leanRest, skin, outline)
        }
        MascotPose.WAVE -> {
            rotate(waveDegrees, pivot = WaveShoulder) { arm(waveUp, skin, outline) }
            arm(waveRest, skin, outline)
        }
    }
}

private fun DrawScope.arm(path: Path, skin: Color, outline: Color) {
    drawPath(path, outline, style = Stroke(width = 13f, cap = StrokeCap.Round))
    drawPath(path, skin, style = Stroke(width = 8f, cap = StrokeCap.Round))
}

private val WaveShoulder = Offset(30f, 62f)
private val leanReach by lazy { svgPath("M88,66 C102,60 114,62 128,70") }
private val leanRest by lazy { svgPath("M32,70 C24,76 22,84 26,90") }
private val waveUp by lazy { svgPath("M30,62 C20,52 14,40 12,28") }
private val waveRest by lazy { svgPath("M90,70 C98,76 100,84 96,90") }

/** Глаза и рот ручкой — поверх бумаги и без наклона: лицо смотрит прямо. */
internal fun DrawScope.face(leftX: Float, rightX: Float, eyeY: Float, mouth: Path) {
    drawCircle(Ink, EYE_RADIUS, Offset(leftX, eyeY))
    drawCircle(Ink, EYE_RADIUS, Offset(rightX, eyeY))
    drawPath(mouth, Ink, style = Stroke(width = 2f, cap = StrokeCap.Round))
}

private const val EYE_RADIUS = 5.5f
