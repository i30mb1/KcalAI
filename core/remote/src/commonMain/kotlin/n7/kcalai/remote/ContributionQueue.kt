package n7.kcalai.remote

import n7.kcalai.database.ContributionDao
import n7.kcalai.model.Log
import kotlin.time.Clock

/**
 * Чем кончилась попытка отправки.
 *
 * @param sent сколько уехало
 * @param reachable сервер отвечал — даже если принял не всё; `null` — слать было
 *        нечего, и о сервере эта попытка ничего не говорит
 */
class UploadOutcome(val sent: Int, val reachable: Boolean?)

/**
 * Отправка накопленных вкладов.
 *
 * Общим кодом, а не внутри воркера: у Android и iOS расписание своё —
 * WorkManager и BGTaskScheduler, — но что считать отправленным, расходиться
 * не должно. Расписание зовёт это и смотрит на [UploadOutcome.reachable].
 *
 * Пачками, но в один вызов: пауза между пачками ставила бы каждую следующую
 * на нарастающее ожидание, хотя ничего не ломалось.
 */
suspend fun uploadPendingContributions(
    dao: ContributionDao,
    uploader: ContributionUploader,
    clock: Clock = Clock.System,
): UploadOutcome {
    var sent = 0
    while (true) {
        val pending = dao.pending(BATCH_SIZE)
        if (pending.isEmpty()) return UploadOutcome(sent, reachable = if (sent > 0) true else null)

        val accepted = uploader.upload(pending)
        if (accepted.isEmpty()) {
            Log.i(TAG, "ни один из ${pending.size} вкладов не принят, попробуем позже")
            return UploadOutcome(sent, reachable = false)
        }

        val sentIds = pending.filter { it.gtin in accepted }.map { it.id }
        dao.markSent(sentIds, clock.now().toEpochMilliseconds())
        sent += sentIds.size
        Log.i(TAG, "отправлено вкладов: ${sentIds.size}")

        // Часть пачки сервер отверг — она останется в очереди и уйдёт в следующий
        // раз. Крутиться на ней сейчас бессмысленно: ответ был бы тем же.
        if (sentIds.size < pending.size || pending.size < BATCH_SIZE) return UploadOutcome(sent, reachable = true)
    }
}

/** Пачка: на одно соединение — десяток продуктов, а не весь дневник сразу. */
private const val BATCH_SIZE = 50
private const val TAG = "ContributionQueue"
