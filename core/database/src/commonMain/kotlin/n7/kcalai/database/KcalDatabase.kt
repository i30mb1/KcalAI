package n7.kcalai.database

import androidx.room.AutoMigration
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
        MascotMessageEntity::class,
        MascotProfileEntity::class,
    ],
    version = KCAL_DB_VERSION,
    exportSchema = true,
    autoMigrations = [
        // Сообщения и профиль персонажей — только новые таблицы, дневник не трогается.
        AutoMigration(from = 4, to = 5),
    ],
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
    abstract fun mascotDao(): MascotDao
}

/** Версия схемы базы. Константой — её читают тесты обновления и выгрузки схемы. */
const val KCAL_DB_VERSION = 5

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
 *
 * Обновление базы — только миграциями. Приложение в Google Play, и в базе
 * дневник людей: «пересоздать при смене версии» стирало бы его у каждого,
 * кто обновился, молча и навсегда. Забытая миграция теперь падает громко,
 * ещё у разработчика. Как менять схему:
 *
 * 1. поднять [KCAL_DB_VERSION];
 * 2. добавить в `@Database` `autoMigrations = [AutoMigration(from = N, to = N + 1)]`
 *    или, если Room сам не справится (переименование, перенос данных),
 *    написать `Migration` и передать её в `addMigrations`;
 * 3. собрать модуль — схема новой версии появится в `schemas/` — и закоммитить её.
 *
 * Стирается база только при понижении версии: через Google Play его не бывает,
 * а разработчику, поставившему старую сборку поверх новой, лучше чистая база,
 * чем падение на старте.
 */
internal fun RoomDatabase.Builder<KcalDatabase>.configured(): KcalDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()
