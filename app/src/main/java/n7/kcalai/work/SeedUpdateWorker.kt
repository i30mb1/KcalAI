package n7.kcalai.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.io.files.Path
import n7.kcalai.KcalApp
import n7.kcalai.remote.updateSeed

/**
 * Докачка справочника с сервера. Что и как качать — общий с iOS код ([updateSeed]),
 * здесь только будильник.
 */
class SeedUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        updateSeed(Path(applicationContext.filesDir.absolutePath), (applicationContext as KcalApp).container.seedSource)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "seed-update"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SeedUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
