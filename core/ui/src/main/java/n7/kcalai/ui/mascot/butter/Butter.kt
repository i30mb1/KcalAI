package n7.kcalai.ui.mascot.butter

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import n7.kcalai.ui.mascot.MascotArt
import n7.kcalai.ui.mascot.face
import n7.kcalai.ui.mascot.paper
import n7.kcalai.ui.mascot.paperStroke
import n7.kcalai.ui.mascot.svgPath

/**
 * Масло — жиры. Мягкое и доброе, чуть подтаивает от похвалы.
 *
 * Узнаётся по завитку на макушке: без него брусок читается как кирпич.
 */
internal object ButterArt : MascotArt {

    override val breathMs = 1550

    private val Front = Color(0xFFF4CD6A)
    private val Top = Color(0xFFFBE6A8)
    private val Curl = Color(0xFFE8B25E)

    private val front by lazy { svgPath("M22,48 C22,42 26,38 32,38 H88 C94,38 98,42 98,48 V84 C98,88 95,91 91,91 H29 C25,91 22,88 22,84 Z") }
    private val top by lazy { svgPath("M24,40 H96 V51 H24 Z") }
    private val drip by lazy { svgPath("M90,52 C90,62 95,64 95,71 C95,76 88,76 88,71 C88,66 86,62 86,52 Z") }
    private val curl by lazy { svgPath("M44,40 C40,28 50,18 60,20 C70,22 72,32 65,35 C60,37 57,32 61,29") }
    private val mouth by lazy { svgPath("M54,77 C57,81 63,81 66,77") }

    override fun DrawScope.draw() {
        rotate(2f, pivot = Offset(60f, 64f)) {
            paper(front, Front)
            paper(top, Top)
            paper(drip, Front)
            paperStroke(curl, Curl, 6f)
        }
        face(46f, 74f, 66f, mouth)
    }
}
