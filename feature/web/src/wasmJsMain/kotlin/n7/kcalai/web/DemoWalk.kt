package n7.kcalai.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import kotlinx.coroutines.delay
import n7.kcalai.model.TreadmillSample

/**
 * Демо прогулки — адрес со `?demo`: дорожка понарошку, 4 км/ч. Числа идут только на странице,
 * в дневник телефона не попадает ничего. Без `?demo` — `null`: числа идут с настоящей дорожки.
 */
@Composable
fun rememberDemoWalk(): TreadmillSample? {
    val demo = remember { "demo" in window.location.search }
    var sample by remember { mutableStateOf<TreadmillSample?>(null) }
    LaunchedEffect(demo) {
        if (!demo) return@LaunchedEffect
        var meters = 0.0
        var seconds = 0
        while (true) {
            sample = TreadmillSample(DEMO_KMH, meters.toInt(), (meters / DEMO_M_PER_KCAL).toInt(), seconds)
            delay(TICK_MS)
            meters += DEMO_KMH / KMH_PER_MPS
            seconds += 1
        }
    }
    return sample
}

private const val TICK_MS = 1_000L
private const val DEMO_KMH = 4.0
private const val DEMO_M_PER_KCAL = 20.0
private const val KMH_PER_MPS = 3.6
