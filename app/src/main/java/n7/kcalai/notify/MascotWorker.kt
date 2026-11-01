package n7.kcalai.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import n7.kcalai.KcalApp
import n7.kcalai.model.Log

/**
 * Раз в полчаса: есть ли персонажам повод написать первыми.
 *
 * Сама работа почти всегда молчит: решает планировщик в мозге, и чаще всего
 * его ответ — «не сейчас». Без сети, без сервера: факты считаются по дневнику
 * на телефоне.
 */
class MascotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @OptIn(ExperimentalTime::class)
    override suspend fun doWork(): Result {
        val notifier = MascotNotifier(applicationContext)
        try {
            val brain = (applicationContext as KcalApp).container.mascotBrain
            brain.write(Clock.System.now(), notifier.canPost())?.let(notifier::post)
        } catch (error: Exception) {
            // Повтор не поможет: следующая попытка и так через полчаса, с новыми фактами.
            Log.e(TAG, "персонажи не смогли написать", error)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MascotWorker"
        private const val NAME = "mascot-messages"

        /** Ставится на каждом старте; KEEP не сбивает уже идущее расписание. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MascotWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private const val PERIOD_MINUTES = 30L
    }
}
