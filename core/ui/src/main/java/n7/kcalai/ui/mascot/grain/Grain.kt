package n7.kcalai.ui.mascot.grain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import n7.kcalai.ui.mascot.MascotArt
import n7.kcalai.ui.mascot.face
import n7.kcalai.ui.mascot.paper
import n7.kcalai.ui.mascot.paperStroke
import n7.kcalai.ui.mascot.svgPath

/**
 * Зёрнышко — углеводы. Бодрое и торопливое, хохолок всегда торчит.
 *
 * Узнаётся по хохолку: без него это зелёный овал, а зелёных овалов вокруг много.
 */
internal object GrainArt : MascotArt {

    // Самое суетливое из троих — и дышит чаще всех.
    override val breathMs = 1150

    override val skin get() = Body
    override val armOutline = Color(0xFF4C8F5A)
    override val portal = Color(0xFF5FA56E)

    private val Body = Color(0xFF7FBF8C)
    private val Tuft = Color(0xFF4C8F5A)
    private val Crease = Color(0xFF3E7A4B)

    private val tuft by lazy { svgPath("M60,22 L53,6 M60,22 L60,2 M60,22 L67,6") }
    private val body by lazy { svgPath("M60,20 C80,20 90,46 90,64 C90,84 76,94 60,94 C44,94 30,84 30,64 C30,46 40,20 60,20 Z") }
    private val crease by lazy { svgPath("M60,28 C57,42 54,52 56,62 C57,72 58,80 60,88") }
    private val mouth by lazy { svgPath("M54,75 C57,79 63,79 66,75") }

    override fun DrawScope.draw() {
        rotate(4f, pivot = Offset(60f, 60f)) {
            paperStroke(tuft, Tuft, 4f)
            paper(body, Body)
            // Бороздка ручкой, а не бумагой: вырезать такую тонкую линию нечем.
            drawPath(crease, Crease, style = Stroke(width = 1.8f, cap = StrokeCap.Round))
        }
        face(48f, 72f, 62f, mouth)
    }
}
