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
import n7.kcalai.KcalApp

/**
 * Отправка заведённых вручную продуктов на сервер.
 *
 * Работа фоновая не ради красоты: продукт человек заводит стоя у полки, где сети
 * обычно нет, — а если ждать её в момент сохранения, форма зависнет на пустом месте.
 * Локально продукт уже сохранён и уже работает; отправка — отдельное обещание,
 * которое WorkManager донесёт, когда сеть появится, хоть после перезагрузки телефона.
 *
 * В очередь попадает только введённое человеком. Данные из Open Food Facts живут
 * в `cached_product` и сюда не доходят физически — иначе ODbL обязал бы нас
 * опубликовать весь серверный пул целиком.
 */
class ContributionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as KcalApp).container
        // Сервера пока нет. Строки ждут его в очереди, а не в бесконечном
        // бэкоффе WorkManager: `retry()` без адреса — это работа, которая
        // будет просыпаться каждые пять часов до конца жизни установки.
        if (!container.serverConfigured) return Result.success()

        val dao = container.database.contributionDao()

        // Пачками, но в один запуск: `retry()` между пачками ставил бы каждую
        // следующую на нарастающую паузу, хотя ничего не ломалось.
        while (true) {
            val pending = dao.pending(BATCH_SIZE)
            if (pending.isEmpty()) return Result.success()

            val accepted = container.contributionUploader.upload(pending)
            if (accepted.isEmpty()) {
                // Сервер недоступен или не принял ничего. Повтор с нарастающей
                // паузой этот случай переживает без вреда: строки просто ждут дальше.
                Log.i(TAG, "ни один из ${pending.size} вкладов не принят, попробуем позже")
                return Result.retry()
            }

            val sentIds = pending.filter { it.gtin in accepted }.map { it.id }
            dao.markSent(sentIds, System.currentTimeMillis())
            Log.i(TAG, "отправлено вкладов: ${sentIds.size}")

            // Часть пачки сервер отверг — она останется в очереди и уйдёт в следующий
            // раз. Крутиться на ней сейчас бессмысленно: ответ был бы тем же.
            if (sentIds.size < pending.size || pending.size < BATCH_SIZE) return Result.success()
        }
    }

    companion object {
        private const val TAG = "ContributionWorker"
        private const val BATCH_SIZE = 50
        private const val WORK_NAME = "contributions"

        /**
         * Ставится в двух местах: после сохранения продукта и один раз при старте
         * приложения. Второе — на случай, если прошлая отправка так и не удалась
         * и с тех пор процесс успел умереть.
         *
         * [ExistingWorkPolicy.KEEP] важнее, чем кажется: без него каждый старт
         * отменял бы уже ждущую своей паузы работу и обнулял бэкофф.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ContributionWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
