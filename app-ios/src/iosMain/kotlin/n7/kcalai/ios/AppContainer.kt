package n7.kcalai.ios

import n7.kcalai.repositories.PhoneSteps
import n7.kcalai.assistant.AppleAssistant
import n7.kcalai.assistant.AppleModelBridge
import n7.kcalai.assistant.FoodAssistant
import n7.kcalai.database.KcalDatabase
import n7.kcalai.database.buildKcalDatabase
import n7.kcalai.feature.diary.OutboxMemory
import n7.kcalai.fooddb.installSeed
import n7.kcalai.graph.AppGraph
import n7.kcalai.mascot.MascotShade
import n7.kcalai.nearby.BonjourDiscovery
import n7.kcalai.nearby.NearbyDiscovery
import platform.Foundation.NSBundle

/**
 * Граф приложения на iOS. Что с чем соединено — общий с Android [AppGraph]; здесь
 * только своё: база и справочник из бандла, адрес сервера из Info.plist, Bonjour,
 * шторка UserNotifications и нейросеть Apple, которую даёт оболочка на Swift.
 *
 * Поиска по смыслу здесь пока нет: модели отпечатков под iOS нет, и поиск идёт по словам.
 */
class AppContainer : AppGraph() {

    override fun openDatabase(): KcalDatabase = buildKcalDatabase()

    /** Шагомер iPhone — Core Motion; без разрешения «Движение и фитнес» шагов телефона нет. */
    val iosSteps by lazy { IosPhoneSteps() }
    override fun phoneSteps(): PhoneSteps = iosSteps
    override fun seedPath(): String = installSeed().toString()

    /** Адрес сервера — из Info.plist, как на Android из BuildConfig: в исходниках его нет. */
    override val serverBaseUrl: String
        get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("KcalServer") as? String).orEmpty()

    override val userAgent = "KcalAI/1.0 (iOS; n7.kcalai)"

    override fun discovery(): NearbyDiscovery = BonjourDiscovery()
    override fun shade(): MascotShade = notifier
    override fun outboxMemory(): OutboxMemory = DefaultsOutboxMemory()

    /** Шторка: сообщения персонажей и ответы после «Записать». */
    val notifier = MascotNotifier()

    /**
     * Нейросеть Apple на телефоне. `null` — оболочка её не дала: на этом
     * устройстве нет Apple Intelligence, и экран живёт без неё.
     */
    var assistant: FoodAssistant? = null
        private set

    /** Зовётся оболочкой на Swift: Foundation Models доступен только оттуда. */
    fun useAppleModel(bridge: AppleModelBridge) {
        assistant = AppleAssistant(bridge, diaryTools)
    }
}
