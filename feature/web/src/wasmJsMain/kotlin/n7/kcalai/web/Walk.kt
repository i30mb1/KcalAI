package n7.kcalai.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Прогулка за страницей: пока по дорожке идут, под дневником оживает бумажный мир, и по нему
 * шагает Зёрнышко в темпе дорожки. Сам мир — JS (papertrails, папка `walk/` рядом со страницей):
 * его грузит `index.html` на первом шаге, страница лишь говорит, идут ли сейчас и как быстро.
 * Цвет фона темы уходит туда же: им подложена страница и тонировано стекло колонки над миром.
 *
 * @param speedKmh темп дорожки; ноль — стоят
 * @return идут ли сейчас
 */
@Composable
fun rememberWalking(pageBg: Color, speedKmh: Double, steps: Int, goal: Int): Boolean {
    val walking = speedKmh > MOVING_KMH
    LaunchedEffect(pageBg) { setPageBg("#" + (pageBg.toArgb() and RGB).toString(HEX).padStart(RGB_DIGITS, '0')) }
    LaunchedEffect(walking, speedKmh, steps, goal) { showWalk(walking, speedKmh, steps, goal) }
    return walking
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

/** Медленнее — дорожка стоит или только трогается. */
private const val MOVING_KMH = 0.3

private const val RGB = 0xFFFFFF
private const val HEX = 16
private const val RGB_DIGITS = 6
