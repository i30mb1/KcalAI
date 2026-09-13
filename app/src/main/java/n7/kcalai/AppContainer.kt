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
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.repositories.FoodDbSource
import n7.kcalai.repositories.FoodRepository
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

    val foodRepository: FoodRepository by lazy {
        FoodRepository(foodDbSource, database.userFoodDao(), foodDbDispatcher)
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
}
