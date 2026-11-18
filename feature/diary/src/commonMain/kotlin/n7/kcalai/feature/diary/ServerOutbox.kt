package n7.kcalai.feature.diary

import n7.kcalai.database.ContributionDao
import n7.kcalai.remote.ContributionUploader
import n7.kcalai.remote.ServerProbe
import n7.kcalai.remote.UploadOutcome
import n7.kcalai.remote.uploadPendingContributions

/** Где помнится «Не сейчас»: на Android — SharedPreferences, на iOS — NSUserDefaults. */
interface OutboxMemory {
    suspend fun declined(): OutboxCount?

    /** `null` — забыть: отправленное больше не ждёт, и отказ от него не глушит следующие продукты. */
    suspend fun remember(declined: OutboxCount?)
}

/** Съёмки этикеток в очереди — только в сборке разработчика: людям обещано, что кадры остаются на телефоне. */
interface ScanQueue {
    suspend fun pending(): Int

    suspend fun upload(): UploadOutcome
}

/**
 * Очередь отправки для экрана — одна на Android и iOS. Считает и шлёт тем же кодом,
 * что фоновая отправка: иначе «отправить» и фон разошлись бы в том, что считать
 * отправленным.
 *
 * @param scans очередь съёмок; `null` — их не шлём (сборка для людей или платформа без съёмок)
 */
class ServerOutbox(
    private val contributions: ContributionDao,
    private val uploader: ContributionUploader,
    private val probe: ServerProbe,
    private val memory: OutboxMemory,
    private val scans: ScanQueue? = null,
    override val retriesInBackground: Boolean = false,
) : DiaryOutbox {

    override suspend fun pending(): OutboxCount =
        OutboxCount(scans = scans?.pending() ?: 0, products = contributions.pendingCount())

    override suspend fun reachable(): Boolean = probe.reachable()

    override suspend fun declined(): OutboxCount? = memory.declined()

    override suspend fun decline(count: OutboxCount) = memory.remember(count)

    override suspend fun send(): OutboxResult {
        val scanned = scans?.upload() ?: UploadOutcome(0, reachable = null)
        val products = uploadPendingContributions(contributions, uploader)
        // Недоступен — когда не ответил ни на одну попытку. Пустая категория попыткой не считается.
        val answers = listOfNotNull(scanned.reachable, products.reachable)
        if (answers.isNotEmpty() && answers.none { it }) return OutboxResult.Unreachable
        // Отправленное больше не ждёт: отказ от него не должен заглушать следующие продукты.
        memory.remember(null)
        return OutboxResult.Sent(scanned.sent, products.sent)
    }
}
