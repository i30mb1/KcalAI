package n7.kcalai.ios

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import n7.kcalai.assistant.AppleAssistant
import n7.kcalai.assistant.AppleModelBridge
import n7.kcalai.assistant.DiaryTools
import n7.kcalai.assistant.FoodAssistant
import n7.kcalai.database.KcalDatabase
import n7.kcalai.database.buildKcalDatabase
import n7.kcalai.fooddb.FoodDb
import n7.kcalai.fooddb.FoodDbFactory
import n7.kcalai.fooddb.installSeed
import n7.kcalai.mascot.FactsBuilder
import n7.kcalai.mascot.MascotBrain
import n7.kcalai.personal.PersonalRepository
import n7.kcalai.remote.ChainedProductSource
import n7.kcalai.remote.ContributionUploader
import n7.kcalai.remote.KcalServerSource
import n7.kcalai.remote.OffProductSource
import n7.kcalai.remote.SeedSource
import n7.kcalai.remote.ServerProbe
import n7.kcalai.remote.VkusVillProductSource
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.repositories.FoodDbSource
import n7.kcalai.repositories.FoodRepository
import n7.kcalai.repositories.RemoteProductSource
import n7.kcalai.resolver.TextFoodResolver
import platform.Foundation.NSBundle
import kotlin.time.Clock

/**
 * Ручная сборка зависимостей — тот же граф, что у `:app` на Android.
 *
 * Нейросети и поиска по смыслу здесь пока нет: экраны и репозитории это
 * предусматривают — модель и отпечатки приходят в них необязательными.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer {

    /**
     * Справочник живёт на одном соединении SQLite, а оно не потокобезопасно.
     * Один поток — единственная дисциплина, которая это гарантирует.
     */
    private val foodDbDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val foodDbLock = Mutex()
    private var openedFoodDb: FoodDb? = null

    private val foodDbSource = FoodDbSource {
        openedFoodDb ?: foodDbLock.withLock {
            openedFoodDb ?: FoodDbFactory.open(
                driver = BundledSQLiteDriver(),
                seedPath = installSeed().toString(),
                // Полный срез Open Food Facts докачивается тем же путём, что и на Android.
                foodPath = null,
            ).also { openedFoodDb = it }
        }
    }

    val database: KcalDatabase by lazy { buildKcalDatabase() }

    private val kcalServer by lazy { KcalServerSource(baseUrl = serverBaseUrl, userAgent = USER_AGENT) }

    /**
     * Порядок тот же, что на Android, и по тем же причинам: в своём пуле лежат
     * российские товары, которых в OFF нет, и только свой ответ мы вправе
     * раздавать дальше — OFF лежит под ODbL. ВкусВилл последний: ему достаются
     * одни настоящие промахи.
     */
    val remoteProductSource: RemoteProductSource by lazy {
        ChainedProductSource(listOf(kcalServer, OffProductSource(USER_AGENT), VkusVillProductSource(USER_AGENT)))
    }

    val contributionUploader: ContributionUploader get() = kcalServer
    val seedSource: SeedSource get() = kcalServer
    val serverProbe: ServerProbe get() = kcalServer

    /** Задан ли адрес сервера. Без него вклады копятся и ждут, справочник не докачивается. */
    val serverConfigured: Boolean get() = serverBaseUrl.isNotBlank()

    val foodRepository: FoodRepository by lazy {
        FoodRepository(
            foodDb = foodDbSource,
            userFoodDao = database.userFoodDao(),
            cachedProductDao = database.cachedProductDao(),
            contributionDao = database.contributionDao(),
            remote = remoteProductSource,
            dispatcher = foodDbDispatcher,
        )
    }

    val diaryRepository: DiaryRepository by lazy {
        DiaryRepository(database.diaryDao(), database.goalDao())
    }

    /**
     * Зона нужна моделям: `createdAt` хранится в UTC, а привычки у человека
     * в местном времени — завтрак в восемь утра остаётся завтраком и после перелёта.
     */
    val personalRepository: PersonalRepository by lazy {
        PersonalRepository(
            diaryDao = database.diaryDao(),
            personalDao = database.personalDao(),
            bodyMetricDao = database.bodyMetricDao(),
            goalDao = database.goalDao(),
            zone = TimeZone.currentSystemDefault(),
        )
    }

    val textResolver: TextFoodResolver by lazy {
        TextFoodResolver(foodRepository, personalRepository)
    }

    /**
     * Инструменты нейросети: поиск по той же базе, что у строки ввода, и итоги
     * дневника. Один экземпляр на приложение — его зовёт оболочка на Swift,
     * а предложения проверяет экран, и помнить, что поиск показывал, они
     * должны общее.
     */
    val diaryTools: DiaryTools by lazy {
        DiaryTools(
            search = { query, limit -> foodRepository.search(query, limit) },
            day = { epochDay -> diaryRepository.observeDay(epochDay).first().entries },
            goal = { epochDay -> diaryRepository.goalFor(epochDay) },
            today = { Clock.System.todayIn(TimeZone.currentSystemDefault()).toEpochDays() },
        )
    }

    /**
     * Нейросеть Apple на телефоне. `null` — оболочка её не дала: на этом
     * устройстве нет Apple Intelligence, и экран живёт без неё, как и на
     * телефонах без Gemini Nano.
     */
    var assistant: FoodAssistant? = null
        private set

    /** Зовётся оболочкой на Swift: Foundation Models доступен только оттуда. */
    fun useAppleModel(bridge: AppleModelBridge) {
        assistant = AppleAssistant(bridge, diaryTools)
    }

    /**
     * Мозг Яйца, Масла и Зёрнышка: когда написать первыми, что ответить, как
     * отозваться на касание. Всё — по дневнику и на телефоне: факты в сеть не уходят.
     */
    val mascotBrain: MascotBrain by lazy {
        val zone = TimeZone.currentSystemDefault()
        MascotBrain(
            dao = database.mascotDao(),
            facts = FactsBuilder(database.diaryDao(), database.goalDao(), personalRepository, zone),
            diaryDao = database.diaryDao(),
            zone = zone,
        )
    }

    /**
     * Адрес сервера — из Info.plist, как на Android из BuildConfig: в исходниках
     * его нет. Пусто — источника нет, всё остаётся на устройстве.
     */
    private val serverBaseUrl: String
        get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("KcalServer") as? String).orEmpty()

    private companion object {
        /**
         * Open Food Facts режет запросы без описательного User-Agent — это их прямое
         * требование к клиентам. Платформа в нём своя: по нему OFF отличает наши
         * сборки друг от друга.
         */
        const val USER_AGENT = "KcalAI/1.0 (iOS; n7.kcalai)"
    }
}
