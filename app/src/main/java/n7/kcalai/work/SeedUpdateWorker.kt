package n7.kcalai.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import n7.kcalai.KcalApp
import n7.kcalai.fooddb.SeedInstaller

/**
 * Докачка справочника с локального сервера.
 *
 * Скачанное не встаёт на место сразу: `FoodDb` держит соединение с текущим файлом,
 * и подменять его под ним нельзя. Файл ложится в `seed.db.next`, а при следующем
 * запуске `SeedInstaller.install` его продвигает. Никаких повторов: нет сети или
 * сервера — попробуем при следующем старте.
 */
class SeedUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as KcalApp).container
        val dir = applicationContext.filesDir

        val manifest = container.seedSource.seedManifest() ?: return Result.success()
        val installed = SeedInstaller.installedDigest(dir)
        val staged = SeedInstaller.stagedDigest(dir)
        if (manifest.sha256.equals(installed, ignoreCase = true) ||
            manifest.sha256.equals(staged, ignoreCase = true)
        ) {
            return Result.success()
        }

        val tmp = File(dir, "seed.db.download")
        if (!container.seedSource.downloadSeed(manifest, tmp)) {
            tmp.delete()
            return Result.success()
        }
        val ready = SeedInstaller.stage(dir, tmp, manifest.sha256)
        Log.i(
            TAG,
            if (ready) "справочник v${manifest.version} докачан, встанет при следующем запуске"
            else "закачка справочника битая, выброшена",
        )
        return Result.success()
    }

    companion object {
        private const val TAG = "SeedUpdateWorker"
        private const val WORK_NAME = "seed-update"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SeedUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
