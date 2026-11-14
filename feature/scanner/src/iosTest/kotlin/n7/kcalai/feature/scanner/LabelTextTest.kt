package n7.kcalai.feature.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Геометрия строк, прочитанных Vision.
 *
 * Разбор таблицы держится на ней целиком: подпись «Белки» связана со своим
 * числом тем, что они на одной высоте. Перепутай верх с низом — и каждая подпись
 * получит чужое число, причём правдоподобное.
 */
class LabelTextTest {

    /** Vision считает снизу, разбор — сверху: строка у верха пачки обязана получить малый y. */
    @Test
    fun `строка у верхнего края кадра получает малый y`() {
        val top = line(boxBottom = 0.9, boxHeight = 0.05)
        val bottom = line(boxBottom = 0.02, boxHeight = 0.05)

        assertTrue(top.centerY < bottom.centerY, "верхняя строка оказалась ниже нижней")
        assertEquals(75f, top.centerY)
        assertEquals(955f, bottom.centerY)
    }

    @Test
    fun `доли кадра становятся пикселями снимка`() {
        val line = visionTextLine(
            text = "Белки",
            confidence = 0.9f,
            boxX = 0.25,
            boxBottom = 0.5,
            boxWidth = 0.5,
            boxHeight = 0.1,
            width = 400,
            height = 1_000,
        )

        assertEquals(200f, line.centerX)
        assertEquals(200f, line.width)
        assertEquals(100f, line.height)
    }

    /** На одной высоте — значит, на одной строке таблицы: это и связывает подпись с числом. */
    @Test
    fun `строки одной высоты считаются одной строкой таблицы`() {
        val label = line(boxBottom = 0.5, boxHeight = 0.04, text = "Белки")
        val value = line(boxBottom = 0.5, boxHeight = 0.04, text = "12,4")

        assertTrue(label.sameRow(value))
    }

    private fun line(
        boxBottom: Double,
        boxHeight: Double,
        text: String = "строка",
    ) = visionTextLine(
        text = text,
        confidence = 1f,
        boxX = 0.1,
        boxBottom = boxBottom,
        boxWidth = 0.3,
        boxHeight = boxHeight,
        width = 400,
        height = 1_000,
    )
}
