package n7.kcalai

import android.app.Application
import n7.kcalai.work.ContributionWorker
import n7.kcalai.work.SeedUpdateWorker

class KcalApp : Application() {

    /** Лёгкий на конструирование: тяжёлое внутри отложено до первого обращения. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Разовый толчок очереди: прошлая отправка могла не удаться, а телефон —
        // перезагрузиться. Работа уникальная и с политикой KEEP, так что уже ждущую
        // своей паузы попытку этот вызов не сбивает.
        ContributionWorker.enqueue(this)
        // Справочник: спросить сервер, нет ли свежее. Ставится при каждом старте,
        // работает только при сети и только если есть адрес сервера.
        if (container.serverConfigured) SeedUpdateWorker.enqueue(this)
    }
}
