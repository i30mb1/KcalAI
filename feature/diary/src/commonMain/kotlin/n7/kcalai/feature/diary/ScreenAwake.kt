package n7.kcalai.feature.diary

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Не давать экрану гаснуть — тонкий адаптер платформы.
 *
 * Когда держать экран, решает общий код (пущенный браузер на связи со страницей
 * дневника); как — платформа: на Android флаг окна, на iOS таймер простоя.
 */
fun interface ScreenAwake {
    fun keep(on: Boolean)
}

/** По умолчанию — ничего: без платформы экран ведёт себя как обычно. */
val LocalScreenAwake = staticCompositionLocalOf { ScreenAwake { } }
