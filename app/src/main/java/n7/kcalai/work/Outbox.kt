package n7.kcalai.work

import android.content.Context
import n7.kcalai.AppContainer
import n7.kcalai.feature.diary.OutboxCount
import n7.kcalai.feature.diary.OutboxMemory
import n7.kcalai.feature.diary.ScanQueue
import n7.kcalai.remote.UploadOutcome

/** «Не сейчас» помнится в SharedPreferences. Что с ним делать — решает общий `ServerOutbox`. */
class PrefsOutboxMemory(context: Context) : OutboxMemory {

    private val prefs = context.getSharedPreferences("outbox", Context.MODE_PRIVATE)

    override suspend fun declined(): OutboxCount? =
        if (prefs.contains(DECLINED_PRODUCTS)) {
            OutboxCount(scans = prefs.getInt(DECLINED_SCANS, 0), products = prefs.getInt(DECLINED_PRODUCTS, 0))
        } else {
            null
        }

    override suspend fun remember(declined: OutboxCount?) {
        val editor = prefs.edit()
        if (declined == null) {
            editor.remove(DECLINED_SCANS).remove(DECLINED_PRODUCTS)
        } else {
            editor.putInt(DECLINED_SCANS, declined.scans).putInt(DECLINED_PRODUCTS, declined.products)
        }
        editor.apply()
    }

    private companion object {
        const val DECLINED_SCANS = "declined_scans"
        const val DECLINED_PRODUCTS = "declined_products"
    }
}

/** Съёмки этикеток — только в сборке разработчика ([AppContainer.silentUploads]). */
class ScanUploads(private val context: Context, private val container: AppContainer) : ScanQueue {

    override suspend fun pending(): Int = ScanUploadWorker.pendingSessions(context).size

    override suspend fun upload(): UploadOutcome = ScanUploadWorker.uploadPending(context, container)
}
