package n7.kcalai.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Сколько страница дневника в браузере живёт, когда дневник на телефоне закрыт
 * или экран погас. Мельком заблокировал телефон — страница не заметит; убрал
 * надолго — связь честно закрывается, и об этом говорит Масло.
 *
 * @param keep сколько держать; `null` — пока система не остановит приложение
 */
enum class PageGrace(val keep: Duration?, val title: String) {
    MIN_1(1.minutes, "1 мин"),
    MIN_5(5.minutes, "5 мин"),
    MIN_15(15.minutes, "15 мин"),
    MIN_60(60.minutes, "Час"),
    FOREVER(null, "Пока система не остановит"),
}
