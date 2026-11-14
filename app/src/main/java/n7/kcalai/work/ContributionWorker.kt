package n7.kcalai.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import n7.kcalai.AppContainer
import n7.kcalai.KcalApp
import n7.kcalai.remote.UploadOutcome
import n7.kcalai.remote.uploadPendingContributions

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
        // У людей фоном ничего не уходит: продукт отправляется по согласию, через Яйцо.
        if (!container.silentUploads) return Result.success()

        val outcome = uploadPending(container)
        // Сервер недоступен или не принял ничего. Повтор с нарастающей
        // паузой этот случай переживает без вреда: строки просто ждут дальше.
        return if (outcome.reachable == false) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "contributions"

        /** Отправить всё, что ждёт. Общая для воркера и кнопки на экране. */
        suspend fun uploadPending(container: AppContainer): UploadOutcome =
            uploadPendingContributions(container.database.contributionDao(), container.contributionUploader)

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
