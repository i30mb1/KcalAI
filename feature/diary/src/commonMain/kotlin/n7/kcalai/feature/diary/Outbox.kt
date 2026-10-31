package n7.kcalai.feature.diary

/** Что скопилось на телефоне и ещё не уехало на сервер. */
data class OutboxCount(val scans: Int, val products: Int) {
    val isEmpty: Boolean get() = scans == 0 && products == 0
}

/** Чем кончилась отправка. */
sealed interface OutboxResult {
    /** Ушло столько; что не ушло — осталось в очереди. */
    data class Sent(val scans: Int, val products: Int) : OutboxResult

    /** Сервер не ответил, ничего не ушло. */
    data object Unreachable : OutboxResult
}

/**
 * Очередь отправки глазами экрана.
 *
 * Воркеры шлют всё сами и молча, и человеку не видно, ушло ли. Экран показывает
 * очередь пузырьком и даёт отправить сейчас — той же логикой, что у воркеров,
 * только с ответом. `null` вместо реализации — сервера нет, пузырька не будет.
 */
interface DiaryOutbox {
    suspend fun pending(): OutboxCount
    suspend fun send(): OutboxResult
}
