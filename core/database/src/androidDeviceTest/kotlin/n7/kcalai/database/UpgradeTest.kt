package n7.kcalai.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Обновление приложения не стирает дневник молча.
 *
 * Раньше база открывалась с `fallbackToDestructiveMigration`: стоило поднять
 * версию схемы и забыть миграцию, как у каждого, кто обновился из Google Play,
 * пропадал весь дневник — без ошибки и без следа. Забытая миграция обязана
 * падать громко, ещё у разработчика, а не стирать данные у людей.
 */
class UpgradeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val path: String get() = context.getDatabasePath(NAME).absolutePath

    @Before
    fun clean() {
        context.deleteDatabase(NAME)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun olderDatabaseWithoutMigrationIsNotWipedSilently() {
        val created = open()
        runBlocking { created.diaryDao().insert(ENTRY) }
        created.close()

        // Притворяемся базой прошлой версии: миграции с неё в коде нет.
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = KCAL_DB_VERSION - 1 }

        val reopened = open()
        val entries = runCatching { runBlocking { reopened.diaryDao().since(0) } }
        reopened.close()

        // Либо миграция есть и запись на месте, либо открытие падает громко.
        // Пустой дневник после обновления — единственный недопустимый исход.
        assertTrue(
            "база прошлой версии открылась пустой — дневник стёрт молча",
            entries.isFailure || entries.getOrThrow().isNotEmpty(),
        )
    }

    private fun open(): KcalDatabase = Room.databaseBuilder<KcalDatabase>(context, path).configured()

    private companion object {
        const val NAME = "upgrade-test.db"

        val ENTRY = DiaryEntryEntity(
            dateEpochDay = 20_000,
            meal = MealType.LUNCH,
            displayName = "Гречка отварная",
            grams = 200,
            kcal100 = 110,
            prot100 = 420,
            fat100 = 110,
            carb100 = 2_130,
            source = EntrySource.TEXT,
            foodRef = "generic:7",
            createdAt = 1_000,
        )
    }
}
