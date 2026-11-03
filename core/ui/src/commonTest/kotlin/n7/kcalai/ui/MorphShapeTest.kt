package n7.kcalai.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Перетекающая форма кнопки.
 *
 * Пружина перелетает за конец перехода, а лепестки звезды торчат дальше круга —
 * форма не должна вылезать за кнопку ни на одном кадре: обрезанный лепесток
 * на краю выглядит как ошибка отрисовки, а не как анимация.
 */
class MorphShapeTest {

    private val morph = Morph(
        RoundedPolygon.circle(numVertices = 8),
        RoundedPolygon.star(numVerticesPerRadius = 4, innerRadius = 0.7f, rounding = CornerRounding(0.4f)),
    )
    private val size = Size(108f, 108f)

    @Test
    fun `в начале перехода — круг во всю кнопку`() {
        val bounds = morph.fitted(progress = 0f, size = size).bounds()

        assertEquals(0f, bounds.left, absoluteTolerance = 0.5f)
        assertEquals(0f, bounds.top, absoluteTolerance = 0.5f)
        assertEquals(size.width, bounds.right, absoluteTolerance = 0.5f)
        assertEquals(size.height, bounds.bottom, absoluteTolerance = 0.5f)
    }

    @Test
    fun `ни на одном кадре форма не выходит за кнопку`() {
        // 1.15 — перелёт пружины за конец перехода.
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.15f)) {
            val bounds = morph.fitted(progress, size).bounds()
            assertTrue(bounds.left >= -0.5f && bounds.top >= -0.5f, "progress=$progress: $bounds")
            assertTrue(bounds.right <= size.width + 0.5f && bounds.bottom <= size.height + 0.5f, "progress=$progress: $bounds")
        }
    }

    /** Границы по точкам самих кривых — того, что ляжет на экран. */
    private fun List<Cubic>.bounds(): Rect {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (cubic in this) {
            for (step in 0..100) {
                val t = step / 100f
                val u = 1f - t
                val x = u * u * u * cubic.anchor0X + 3 * u * u * t * cubic.control0X + 3 * u * t * t * cubic.control1X + t * t * t * cubic.anchor1X
                val y = u * u * u * cubic.anchor0Y + 3 * u * u * t * cubic.control0Y + 3 * u * t * t * cubic.control1Y + t * t * t * cubic.anchor1Y
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
        return Rect(left, top, right, bottom)
    }
}
