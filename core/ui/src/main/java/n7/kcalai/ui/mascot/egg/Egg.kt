package n7.kcalai.ui.mascot.egg

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import n7.kcalai.ui.mascot.MascotArt
import n7.kcalai.ui.mascot.face
import n7.kcalai.ui.mascot.paper
import n7.kcalai.ui.mascot.paperDot
import n7.kcalai.ui.mascot.svgPath

/**
 * Яйцо — белок. Собранное, немного правильное, носит скорлупу как каску.
 *
 * Узнаётся по скорлупе-шапке с зубцами: без неё это просто белый овал.
 */
internal object EggArt : MascotArt {

    override val breathMs = 1300

    override val skin get() = White
    override val armOutline = Color(0xFFD8C8AE)
    override val portal = Color(0xFFE0648C)

    private val White = Color(0xFFFFF8EE)
    private val Shell = Color(0xFFE58BA8)
    private val Blush = Color(0xFFF3B5C8)

    private val body by lazy { svgPath("M60,26 C80,26 92,52 92,68 C92,84 78,94 60,94 C42,94 28,84 28,68 C28,52 40,26 60,26 Z") }
    private val shell by lazy { svgPath("M34,46 C38,20 48,8 60,8 C72,8 82,20 86,46 L78,40 L70,48 L60,40 L50,48 L42,40 Z") }
    private val mouth by lazy { svgPath("M55,77 C57,80 63,80 65,76") }

    override fun DrawScope.draw() {
        // Вырезку положили чуть криво — так она выглядит положенной руками.
        rotate(-3f, pivot = Offset(60f, 60f)) {
            paper(body, White)
            paper(shell, Shell)
            paperDot(Offset(40f, 74f), 5f, Blush)
            paperDot(Offset(80f, 74f), 5f, Blush)
        }
        face(50f, 70f, 64f, mouth)
    }
}
