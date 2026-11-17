package n7.kcalai.feature.diary

/** Что скопилось на телефоне и ещё не уехало на сервер. */
data class OutboxCount(val scans: Int, val products: Int) {
    val isEmpty: Boolean get() = scans == 0 && products == 0

    /** Есть ли что-то сверх отклонённого «Не сейчас»; `null` — ничего не отклоняли. */
    fun exceeds(declined: OutboxCount?): Boolean =
        declined == null || scans > declined.scans || products > declined.products
}

/**
 * Предложение Яйца поделиться. Продукты — главное: ради них человек соглашается,
 * их потом найдут по коду другие. В очереди и новые продукты, и поправки к уже
 * известным, поэтому речь о том, что человек знает больше базы, а не что в ней чего-то нет.
 * Съёмки этикеток бывают только в сборке разработчика.
 */
fun outboxOffer(count: OutboxCount): String {
    val scans = if (count.scans > 0) "Съёмки этикеток (${count.scans}) уйдут заодно." else null
    if (count.products == 0) return "Накопились съёмки этикеток: ${count.scans}. Отправить на сервер?"
    val n = count.products
    val products = if (n % 10 == 1 && n % 100 != 11) "продукте" else "продуктах"
    return listOfNotNull(
        "Ты знаешь о $n $products больше общей базы. Поделимся, чтобы и другие находили их по коду?",
        scans,
    ).joinToString(" ")
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

    /**
     * Отвечает ли сервер прямо сейчас.
     *
     * Пузырёк с кнопкой «Отправить» — предложение, которое обязано сработать:
     * пока сервер не отвечает, он только шумит в ленте.
     */
    suspend fun reachable(): Boolean

    suspend fun send(): OutboxResult

    /** Что человек отклонил «Не сейчас»; `null` — не отклонял. Те же продукты второй раз не предлагаются. */
    suspend fun declined(): OutboxCount?

    suspend fun decline(count: OutboxCount)

    /**
     * Донесут ли неотправленное фоновые воркеры. В сборке для людей — нет: там продукт
     * уходит только с согласия, и ответ на молчащий сервер — спросить ещё раз, а не обещать.
     */
    val retriesInBackground: Boolean
}
