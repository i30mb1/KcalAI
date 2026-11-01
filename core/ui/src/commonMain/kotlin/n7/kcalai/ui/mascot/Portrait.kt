package n7.kcalai.ui.mascot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import n7.kcalai.model.Mascot
import n7.kcalai.ui.LightKcalColors
import n7.kcalai.ui.Macro

/**
 * Аватарка для шторки: персонаж в круге цвета своей плитки.
 *
 * Тот же рисунок, что на экране, — в шторке человек узнаёт того, кто ему пишет.
 * Рисуется без экрана и без темы: в фоновой работе их нет, а у аватарки
 * в шторке своя подложка, светлая на любой теме телефона.
 */
fun mascotPortrait(mascot: Mascot, sizePx: Int): ImageBitmap {
    val bitmap = ImageBitmap(sizePx, sizePx)
    val side = sizePx.toFloat()
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(side, side)) {
        drawCircle(mascot.tile, radius = side / 2)
        val scale = side * FILL / VIEW_W
        translate(left = (side - VIEW_W * scale) / 2, top = (side - VIEW_H * scale) / 2 + side * 0.03f) {
            scale(scale, scale, pivot = Offset.Zero) {
                val art = mascot.art
                with(art) { drawBody() }
                face(art.face, MascotFace.CALM, Offset.Zero, blink = 0f)
            }
        }
    }
    return bitmap
}

/** Доля круга, которую занимает персонаж: остальное — поля, чтобы круг его не срезал. */
private const val FILL = 0.74f

private val Mascot.tile
    get() = when (macro) {
        Macro.PROTEIN -> LightKcalColors.protein.tile
        Macro.FAT -> LightKcalColors.fat.tile
        Macro.CARB -> LightKcalColors.carb.tile
    }
