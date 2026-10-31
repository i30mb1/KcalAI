package n7.kcalai.database

import androidx.room.Database
import androidx.room.ConstructedBy
import androidx.room.RoomDatabaseConstructor
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        DiaryEntryEntity::class,
        UserFoodEntity::class,
        CachedProductEntity::class,
        ContributionEntity::class,
        DailyGoalEntity::class,
        BodyMetricEntity::class,
        SuggestionEventEntity::class,
        PortionSampleEntity::class,
        RankerWeightEntity::class,
        RankerStateEntity::class,
        MealGapDismissEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@ConstructedBy(KcalDatabaseConstructor::class)
abstract class KcalDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun userFoodDao(): UserFoodDao
    abstract fun cachedProductDao(): CachedProductDao
    abstract fun contributionDao(): ContributionDao
    abstract fun goalDao(): GoalDao
    abstract fun bodyMetricDao(): BodyMetricDao
    abstract fun personalDao(): PersonalDao
}

/** Реализацию для каждой платформы генерирует Room. */
@Suppress("KotlinNoActualForExpect")
expect object KcalDatabaseConstructor : RoomDatabaseConstructor<KcalDatabase> {
    override fun initialize(): KcalDatabase
}

/**
 * Общая для платформ настройка: платформа даёт только builder с путём к файлу.
 *
 * Тот же bundled-драйвер, что и у справочника: системный SQLite на части
 * устройств собран без FTS5, и полагаться на него нельзя.
 */
internal fun RoomDatabase.Builder<KcalDatabase>.configured(): KcalDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        // Приложение не выпущено: пересоздать базу дешевле, чем писать
        // миграции для схемы, которая ещё меняется каждый день.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
