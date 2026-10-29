package n7.kcalai.ui.mascot.egg

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import n7.kcalai.ui.mascot.MascotArt
import n7.kcalai.ui.mascot.face
import n7.kcalai.ui.mascot.paper
import n7.kcalai.ui.mascot.paperDot
import n7.kcalai.ui.mascot.svgPath

/**
 * Яйцо — белок. Собранное, немного правильное, носит скорлупу как каску.
 *
 * Вытянуто вверх, как настоящее яйцо: приплюснутый овал с шапкой читался как
 * луковица. Скорлупа — верх того же контура, отрезанный трещиной зубцами,
 * поэтому сидит на яйце точно, а не накладкой поверх.
 */
internal object EggArt : MascotArt {

    override val breathMs = 1300

    override val skin get() = White
    override val armOutline = Color(0xFFD8C8AE)

    private val White = Color(0xFFFFF8EE)
    private val Shell = Color(0xFFE58BA8)
    private val Blush = Color(0xFFF3B5C8)

    private val body by lazy { svgPath("M60,4 C78,4 89,38 89,62 C89,83 76,96 60,96 C44,96 31,83 31,62 C31,38 42,4 60,4 Z") }
    private val crack by lazy { svgPath("M0,0 H120 V36 L102,32 L92,41 L82,32 L71,41 L60,32 L49,41 L38,32 L28,41 L18,32 L0,38 Z") }
    private val mouth by lazy { svgPath("M55,78 C57,81 63,81 65,77") }

    override fun DrawScope.draw() {
        // Вырезку положили чуть криво — так она выглядит положенной руками.
        rotate(-3f, pivot = Offset(60f, 60f)) {
            paper(body, White)
            clipPath(body) { drawPath(crack, Shell) }
            paperDot(Offset(42f, 74f), 5f, Blush)
            paperDot(Offset(78f, 74f), 5f, Blush)
        }
        face(50f, 70f, 63f, mouth)
    }
}
