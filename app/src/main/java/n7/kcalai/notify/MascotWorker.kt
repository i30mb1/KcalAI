package n7.kcalai.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import n7.kcalai.KcalApp

/**
 * Раз в полчаса: есть ли персонажам повод написать первыми.
 *
 * Сама работа почти всегда молчит: решает планировщик в мозге, и чаще всего
 * его ответ — «не сейчас». Без сети, без сервера: факты считаются по дневнику
 * на телефоне.
 */
class MascotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /** Решает и пишет общий с iOS код. Сбой повторять незачем: следующая попытка через полчаса. */
    override suspend fun doWork(): Result {
        (applicationContext as KcalApp).container.mascotRoutines.tick()
        return Result.success()
    }

    companion object {
        private const val NAME = "mascot-messages"

        /** Ставится на каждом старте; KEEP не сбивает уже идущее расписание. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MascotWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private const val PERIOD_MINUTES = 30L
    }
}
