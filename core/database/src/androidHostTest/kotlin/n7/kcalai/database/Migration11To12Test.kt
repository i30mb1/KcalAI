package n7.kcalai.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Обновление с версии 11 добавляет в профиль, сколько держать страницу дневника,
 * когда телефон убран, — и не трогает ни дневник, ни имя: у обновившегося — пять минут.
 */
class Migration11To12Test {

    private val connection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    @Test
    fun `имя на месте, страница держится пять минут`() {
        createSqlOf(11).forEach(connection::execSQL)
        connection.execSQL(
            "INSERT INTO diary_entry (dateEpochDay, meal, displayName, grams, kcal100, prot100, fat100, " +
                "carb100, source, foodRef, createdAt, fiber100) " +
                "VALUES (20000, 'LUNCH', 'Гречка отварная', 200, 110, 420, 110, 2130, 'TEXT', 'generic:1', 1000, 280)"
        )

        connection.execSQL("INSERT INTO mascot_profile (id, name, nameAsked, consent) VALUES (0, 'Женя', 1, 'ALLOWED')")

        KcalDatabase_AutoMigration_11_12_Impl().migrate(connection)

        assertEquals("Гречка отварная", connection.text("SELECT displayName FROM diary_entry"))
        assertEquals("Женя", connection.text("SELECT name FROM mascot_profile"))
        assertEquals("MIN_5", connection.text("SELECT pageGrace FROM mascot_profile"))
        assertEquals(
            COLUMN.findAll(createSqlOf(12).single { "`mascot_profile`" in it }).map { it.groupValues[1] to it.groupValues[2] }.toList(),
            connection.columns("mascot_profile"),
        )
    }

    private fun SQLiteConnection.columns(table: String): List<Pair<String, String>> =
        prepare("PRAGMA table_info($table)").use { statement ->
            buildList {
                while (statement.step()) add(statement.getText(1) to statement.getText(2))
            }
        }

    private fun SQLiteConnection.text(sql: String): String =
        prepare(sql).use { statement ->
            check(statement.step()) { "пусто: $sql" }
            statement.getText(0)
        }

    private fun createSqlOf(version: Int): List<String> =
        CREATE_TABLE.findAll(File("schemas/n7.kcalai.database.KcalDatabase/$version.json").readText())
            .map { match -> match.groupValues[2].replace("\${TABLE_NAME}", match.groupValues[1]) }
            .toList()

    private companion object {
        val CREATE_TABLE = Regex(""""tableName": "(\w+)",\s+"createSql": "([^"]*)"""")
        val COLUMN = Regex("""`(\w+)` (INTEGER|TEXT|REAL|BLOB)""")
    }
}
