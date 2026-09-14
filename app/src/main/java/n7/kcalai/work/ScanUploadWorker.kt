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
import java.util.concurrent.TimeUnit
import n7.kcalai.KcalApp
import n7.kcalai.feature.scanner.labelScansDirectory
import n7.kcalai.remote.ScanFrame

/**
 * Отправка сессий съёмки этикеток на локальный сервер — материал для правки разбора.
 *
 * Сессии, которые ушли, помечаются файлом `.uploaded` и остаются на месте:
 * прополку «последние пять» делает `LabelRecorder`, а отправка не вправе
 * удалять то, что человек, может быть, ещё захочет посмотреть на телефоне.
 */
class ScanUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as KcalApp).container
        if (!container.serverConfigured) return Result.success()

        val sessions = labelScansDirectory(applicationContext)
            .listFiles { file -> file.isDirectory && file.name.startsWith("scan-") }
            .orEmpty()
            .filter { !File(it, MARKER).exists() && File(it, "readings.txt").isFile }
            .sortedBy { it.name }
        if (sessions.isEmpty()) return Result.success()

        var failed = false
        for (session in sessions) {
            val frames = session.listFiles { file -> file.name.endsWith(".jpg") }
                .orEmpty()
                .sortedBy { it.name }
                .map { ScanFrame(it.name, it.readBytes()) }
            if (frames.isEmpty()) continue

            val sent = container.scanUploader.uploadScan(
                id = session.name,
                readings = File(session, "readings.txt").readText(),
                frames = frames,
            )
            if (sent) {
                File(session, MARKER).writeText("")
                Log.i(TAG, "сессия ${session.name} отправлена (${frames.size} кадров)")
            } else {
                failed = true
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "ScanUploadWorker"
        private const val WORK_NAME = "scan-upload"
        const val MARKER = ".uploaded"

        /**
         * Задержка — потому что `LabelRecorder.save` пишет кадры в отдельном потоке
         * уже после закрытия экрана, и воркер без паузы застал бы каталог пустым.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScanUploadWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
