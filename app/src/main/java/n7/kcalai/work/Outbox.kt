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
 * разошлись бы в том, что считать отправленным.
 */
class Outbox(
    private val context: Context,
    private val container: AppContainer,
) : DiaryOutbox {

    override suspend fun pending(): OutboxCount = OutboxCount(
        scans = ScanUploadWorker.pendingSessions(context).size,
        products = container.database.contributionDao().pendingCount(),
    )

    override suspend fun send(): OutboxResult {
        val scans = ScanUploadWorker.uploadPending(context, container)
        val products = ContributionWorker.uploadPending(container)
        // Недоступен — когда не ответил ни на одну попытку. Пустая категория попыткой не считается.
        val answers = listOfNotNull(scans.reachable, products.reachable)
        if (answers.isNotEmpty() && answers.none { it }) return OutboxResult.Unreachable
        return OutboxResult.Sent(scans.sent, products.sent)
    }
}

/**
 * Итог одной попытки отправки.
 *
 * @param reachable сервер отвечал — даже если принял не всё; `null` — слать было
 *        нечего, и о сервере эта попытка ничего не говорит
 */
class UploadOutcome(val sent: Int, val reachable: Boolean?)
