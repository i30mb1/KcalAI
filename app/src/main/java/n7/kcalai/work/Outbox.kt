package n7.kcalai.work

import android.content.Context
import n7.kcalai.AppContainer
import n7.kcalai.feature.diary.DiaryOutbox
import n7.kcalai.feature.diary.OutboxCount
import n7.kcalai.feature.diary.OutboxResult

/**
 * Очередь отправки для экрана: считает и шлёт тем же кодом, что воркеры.
 *
 * Дублировать логику в кнопке нельзя: тогда «отправить» и фоновая отправка
 * разошлись бы в том, что считать отправленным. Съёмки этикеток — только
 * у разработчика ([AppContainer.silentUploads]): людям обещано, что кадры
 * остаются на телефоне.
 */
class Outbox(
    private val context: Context,
    private val container: AppContainer,
) : DiaryOutbox {

    private val prefs = context.getSharedPreferences("outbox", Context.MODE_PRIVATE)

    override val retriesInBackground: Boolean get() = container.silentUploads

    override suspend fun pending(): OutboxCount = OutboxCount(
        scans = if (container.silentUploads) ScanUploadWorker.pendingSessions(context).size else 0,
        products = container.database.contributionDao().pendingCount(),
    )

    override suspend fun reachable(): Boolean = container.serverProbe.reachable()

    override suspend fun declined(): OutboxCount? =
        if (prefs.contains(DECLINED_PRODUCTS)) {
            OutboxCount(scans = prefs.getInt(DECLINED_SCANS, 0), products = prefs.getInt(DECLINED_PRODUCTS, 0))
        } else {
            null
        }

    override suspend fun decline(count: OutboxCount) {
        prefs.edit().putInt(DECLINED_SCANS, count.scans).putInt(DECLINED_PRODUCTS, count.products).apply()
    }

    override suspend fun send(): OutboxResult {
        val scans = if (container.silentUploads) ScanUploadWorker.uploadPending(context, container) else UploadOutcome(0, null)
        val products = ContributionWorker.uploadPending(container)
        // Недоступен — когда не ответил ни на одну попытку. Пустая категория попыткой не считается.
        val answers = listOfNotNull(scans.reachable, products.reachable)
        if (answers.isNotEmpty() && answers.none { it }) return OutboxResult.Unreachable
        // Отправленное больше не ждёт: отказ от него не должен заглушать следующие продукты.
        prefs.edit().remove(DECLINED_SCANS).remove(DECLINED_PRODUCTS).apply()
        return OutboxResult.Sent(scans.sent, products.sent)
    }

    private companion object {
        const val DECLINED_SCANS = "declined_scans"
        const val DECLINED_PRODUCTS = "declined_products"
    }
}

/**
 * Итог одной попытки отправки.
 *
 * @param reachable сервер отвечал — даже если принял не всё; `null` — слать было
 *        нечего, и о сервере эта попытка ничего не говорит
 */
class UploadOutcome(val sent: Int, val reachable: Boolean?)
