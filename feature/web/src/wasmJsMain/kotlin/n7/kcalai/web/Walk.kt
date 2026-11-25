package n7.kcalai.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.browser.window
import kotlinx.coroutines.delay

/**
 * Прогулка за страницей: пока человек идёт, под дневником оживает бумажный мир, и по его дорогам
 * шагает Зёрнышко. Сам мир — JS (papertrails, папка `walk/` рядом со страницей): его грузит
 * `index.html` только на старте прогулки, страница лишь говорит, идут ли сейчас и как быстро.
 * Цвет фона темы уходит туда же: им подложена страница и тонировано стекло колонки над миром.
 *
 * Шагов с дорожки пока нет — прогулку включает адрес со `?walk`: так видно мир и героя.
 */
@Composable
fun rememberWalking(pageBg: Color): Boolean {
    val demo = remember { "walk" in window.location.search }
    var steps by remember { mutableIntStateOf(0) }
    LaunchedEffect(pageBg) { setPageBg("#" + (pageBg.toArgb() and RGB).toString(HEX).padStart(RGB_DIGITS, '0')) }
    LaunchedEffect(demo) {
        if (!demo) return@LaunchedEffect
        while (true) {
            showWalk(on = true, kmh = DEMO_KMH, steps = steps, goal = DEMO_GOAL)
            delay(TICK_MS)
            steps += DEMO_STEPS_PER_TICK
        }
    }
    return demo
}

/** Мост к миру прогулки: `window.KcalWalk` читает автопилот героя (walk/kcal/autopilot.js). */
private fun showWalk(on: Boolean, kmh: Double, steps: Int, goal: Int): Unit = js(
    """{
        window.KcalWalk = { vis: kmh, steps: steps, goal: goal };
        if (on && window.KcalWalkStart) window.KcalWalkStart();
        document.body.classList.toggle('walking', on);
    }""",
)

private fun setPageBg(css: String): Unit = js("document.documentElement.style.setProperty('--page-bg', css)")

/** Анимация фона страницы — в такт проявлению мира (`transition` холста в index.html). */
internal const val WALK_FADE_MS = 1_200

private const val RGB = 0xFFFFFF
private const val HEX = 16
private const val RGB_DIGITS = 6
private const val DEMO_KMH = 4.0
private const val DEMO_GOAL = 8_000
private const val TICK_MS = 500L
private const val DEMO_STEPS_PER_TICK = 3
