package n7.kcalai.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

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
    version = 3,
    exportSchema = false,
)
abstract class KcalDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun userFoodDao(): UserFoodDao
    abstract fun cachedProductDao(): CachedProductDao
    abstract fun contributionDao(): ContributionDao
    abstract fun goalDao(): GoalDao
    abstract fun bodyMetricDao(): BodyMetricDao
    abstract fun personalDao(): PersonalDao

    companion object {
        /**
         * Тот же bundled-драйвер, что и у справочника: системный SQLite на части
         * устройств собран без FTS5, и полагаться на него нельзя.
         */
        fun build(context: Context): KcalDatabase =
            Room.databaseBuilder(context, KcalDatabase::class.java, "kcal.db")
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                // Приложение не выпущено: пересоздать базу дешевле, чем писать
                // миграции для схемы, которая ещё меняется каждый день.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
