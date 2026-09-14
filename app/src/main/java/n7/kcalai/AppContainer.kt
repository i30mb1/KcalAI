package n7.kcalai

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import n7.kcalai.database.KcalDatabase
import n7.kcalai.fooddb.FoodDb
import n7.kcalai.fooddb.FoodDbFactory
import n7.kcalai.fooddb.SeedInstaller
import n7.kcalai.personal.PersonalRepository
import n7.kcalai.remote.ChainedProductSource
import n7.kcalai.remote.ContributionUploader
import n7.kcalai.remote.KcalServerSource
import n7.kcalai.remote.OffProductSource
import n7.kcalai.remote.ScanUploader
import n7.kcalai.remote.SeedSource
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.repositories.FoodDbSource
import n7.kcalai.repositories.FoodRepository
import n7.kcalai.repositories.RemoteProductSource
import n7.kcalai.resolver.TextFoodResolver

/**
 * Ручная сборка зависимостей.
 *
 * Dagger из дизайна появится, когда резолверов станет больше одного и понадобится
 * мультибиндинг. Пока граф — пять объектов, и контейнер честнее генерации кода.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

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
                seedPath = SeedInstaller.install(appContext),
                // Полный срез Open Food Facts докачивается в Плане 4.
                foodPath = null,
            ).also { openedFoodDb = it }
        }
    }

    val database: KcalDatabase by lazy { KcalDatabase.build(appContext) }

    /**
     * Свой сервер. Пока пуст — источник просто пропускается, и цепочка идёт в OFF.
     *
     * Сервер пишется отдельно и клиент не блокирует: весь путь до него уже написан
     * и проверяется на OFF, а появление адреса здесь ничего больше не потребует.
     */
    private val kcalServer by lazy { KcalServerSource(baseUrl = SERVER_BASE_URL, userAgent = USER_AGENT) }

    /**
     * Свой сервер спрашивается раньше Open Food Facts.
     *
     * Порядок содержательный: в своём пуле лежат российские товары, заведённые
     * людьми вручную, — тех самых, которых в OFF нет и не появится. И только ответ
     * своего сервера мы вправе раздавать дальше: OFF лежит под ODbL.
     */
    val remoteProductSource: RemoteProductSource by lazy {
        ChainedProductSource(listOf(kcalServer, OffProductSource(USER_AGENT)))
    }

    val contributionUploader: ContributionUploader get() = kcalServer
    val seedSource: SeedSource get() = kcalServer
    val scanUploader: ScanUploader get() = kcalServer

    /** Задан ли адрес сервера. Без него вклады и сессии копятся и ждут, справочник не докачивается. */
    val serverConfigured: Boolean get() = SERVER_BASE_URL.isNotBlank()

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
            zone = ZoneId.systemDefault(),
        )
    }

    val textResolver: TextFoodResolver by lazy {
        TextFoodResolver(foodRepository, personalRepository)
    }

    private companion object {
        /** Из `local.properties` (`kcal.server=…`). Пустая строка означает «источника нет». */
        val SERVER_BASE_URL: String = BuildConfig.KCAL_SERVER

        /**
         * Open Food Facts режет запросы без описательного User-Agent — это их прямое
         * требование к клиентам, а не рекомендация. Когда появится публичный адрес
         * проекта или почта для связи, их стоит подставить сюда: так у OFF будет
         * кого спросить, если наш клиент начнёт вести себя неправильно.
         */
        const val USER_AGENT = "KcalAI/1.0 (Android; n7.kcalai)"
    }
}
