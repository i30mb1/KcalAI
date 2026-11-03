package n7.kcalai.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Обновление с версии 5 добавляет кэш отпечатков и не трогает дневник.
 *
 * База собирается настоящим SQLite по выгруженной схеме прошлой версии — тем, что
 * лежит на телефонах людей, — и по ней проходит сгенерированная миграция.
 */
class Migration5To6Test {

    private val connection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    @Test
    fun `дневник на месте, кэш отпечатков появился и совпадает со схемой версии 6`() {
        createSqlOf(5).forEach(connection::execSQL)
        connection.execSQL(
            "INSERT INTO diary_entry (dateEpochDay, meal, displayName, grams, kcal100, prot100, fat100, " +
                "carb100, source, foodRef, createdAt) " +
                "VALUES (20000, 'LUNCH', 'Гречка отварная', 200, 110, 420, 110, 2130, 'TEXT', 'generic:7', 1000)"
        )

        KcalDatabase_AutoMigration_5_6_Impl().migrate(connection)

        assertEquals("Гречка отварная", connection.text("SELECT displayName FROM diary_entry"))
        assertEquals(
            createSqlOf(6).single { "text_embedding" in it }.replace("IF NOT EXISTS ", ""),
            connection.text("SELECT sql FROM sqlite_master WHERE name = 'text_embedding'"),
        )
    }

    private fun SQLiteConnection.text(sql: String): String =
        prepare(sql).use { statement ->
            check(statement.step()) { "пусто: $sql" }
            statement.getText(0)
        }

    /** Таблицы версии [version] из `schemas/`: без индексов и служебной таблицы Room — здесь они не нужны. */
    private fun createSqlOf(version: Int): List<String> =
        CREATE_TABLE.findAll(File("schemas/n7.kcalai.database.KcalDatabase/$version.json").readText())
            .map { match ->
                match.groupValues[2].replace("\${TABLE_NAME}", match.groupValues[1])
            }
            .toList()

    private companion object {
        val CREATE_TABLE = Regex(""""tableName": "(\w+)",\s+"createSql": "([^"]*)"""")
    }
}
