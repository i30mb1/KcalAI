package n7.kcalai.feature.diary

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Поделиться текстом через систему — тонкий адаптер платформы.
 *
 * Чем делиться (адресом страницы дневника), решает общий код; как — платформа:
 * на Android окно выбора приложений, на iOS лист «Поделиться». Скинуть ссылку себе
 * в мессенджер и открыть её на компьютере — быстрее, чем перепечатывать адрес.
 */
fun interface ShareText {
    fun share(text: String)
}

/** По умолчанию — ничего: без платформы делиться некуда. */
val LocalShareText = staticCompositionLocalOf { ShareText { } }
