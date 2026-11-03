package n7.kcalai.feature.diary

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.MeshGradientPainter
import androidx.compose.ui.unit.dp
import n7.kcalai.ui.KcalColors
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.frost
import n7.kcalai.ui.frostSupported

/**
 * Фон шапки и строки ввода: матовое стекло с mesh-градиентом из цветов трёх макросов.
 *
 * Лента идёт под обеими полосами. Где система умеет ([frostSupported]), уходящие
 * под них реплики видны размытыми; где не умеет, фон под градиентом плотный.
 *
 * @param flipped пятна макросов у нижнего края, а не у верхнего: у строки ввода
 *        цветом отмечен край экрана, как у шапки, а не граница с лентой
 */
@Composable
fun Modifier.meshGlass(flipped: Boolean = false): Modifier {
    val colors = KcalTheme.colors
    val mesh = remember(colors, flipped) { glassMesh(colors, flipped) }
    val scrimAlpha = glassScrimAlpha(frostSupported)
    return this
        .frost(blur = FROST_BLUR, saturation = FROST_SATURATION)
        .drawBehind {
            drawRect(colors.bg, alpha = scrimAlpha)
            with(mesh) { draw(size, alpha = MESH_ALPHA) }
        }
}

/**
 * Плотность фона под mesh-градиентом.
 *
 * Со стеклом фон полупрозрачный: сквозь него видна размытая лента. Без стекла
 * реплики читались бы поверх цифр и набранного — фон плотный.
 */
internal fun glassScrimAlpha(frosted: Boolean): Float = if (frosted) 0.5f else 1f

/**
 * Пятна трёх макросов по сетке 3×3: белки слева, жиры справа, углеводы у другого
 * края, светлый фон в середине — под цифрами и текстом он и нужен. Цвета — фоны
 * плиток: градиент той же семьи, что плитки над ним, только бледнее.
 */
private fun glassMesh(colors: KcalColors, flipped: Boolean): MeshGradientPainter {
    val grid = listOf(
        listOf(colors.protein.tile, colors.bg, colors.fat.tile),
        listOf(colors.protein.tile, colors.bg, colors.fat.tile),
        listOf(colors.carb.tile, colors.carb.tile, colors.bg),
    ).let { if (flipped) it.reversed() else it }
    return MeshGradientPainter(rows = 2, columns = 2, hasBicubicColor = true) {
        for (row in 0..2) {
            for (column in 0..2) {
                setVertex(row, column, Offset(column / 2f, row / 2f), grid[row][column])
            }
        }
    }
}

/** Насколько проступает градиент: плитки того же цвета должны остаться плитками. */
private const val MESH_ALPHA = 0.6f
private val FROST_BLUR = 24.dp
private const val FROST_SATURATION = 1.6f
