package n7.kcalai.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Обновление с версии 8 заводит предложения соседа — и не трогает дневник.
 *
 * Таблица новая и пустая: у того, кто обновился, записи остаются на месте,
 * а предложений у него и не было. База собирается настоящим SQLite по выгруженной
 * схеме прошлой версии, и по ней проходит сгенерированная миграция.
 */
class Migration8To9Test {

    private val connection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    @Test
    fun `записанное на месте предложений у обновившегося нет`() {
        createSqlOf(8).forEach(connection::execSQL)
        connection.execSQL(
            "INSERT INTO diary_entry (dateEpochDay, meal, displayName, grams, kcal100, prot100, fat100, " +
                "carb100, source, foodRef, createdAt, fiber100) " +
                "VALUES (20000, 'LUNCH', 'Гречка отварная', 200, 110, 420, 110, 2130, 'TEXT', 'generic:1', 1000, 280)"
        )

        KcalDatabase_AutoMigration_8_9_Impl().migrate(connection)

        assertEquals("Гречка отварная", connection.text("SELECT displayName FROM diary_entry"))
        assertEquals("280", connection.text("SELECT fiber100 FROM diary_entry"))
        assertEquals("0", connection.text("SELECT count(*) FROM peer_offer"))

        // Столбцы, а не текст CREATE: сравниваем с тем, что обещает схема версии 9.
        assertEquals(
            COLUMN.findAll(createSqlOf(9).single { "`peer_offer`" in it }).map { it.groupValues[1] to it.groupValues[2] }.toList(),
            connection.columns("peer_offer"),
        )
    }

    /** Имя и тип каждого столбца — по порядку, как их видит SQLite. */
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

    /** Таблицы версии [version] из `schemas/`: без индексов и служебной таблицы Room — здесь они не нужны. */
    private fun createSqlOf(version: Int): List<String> =
        CREATE_TABLE.findAll(File("schemas/n7.kcalai.database.KcalDatabase/$version.json").readText())
            .map { match -> match.groupValues[2].replace("\${TABLE_NAME}", match.groupValues[1]) }
            .toList()

    private companion object {
        val CREATE_TABLE = Regex(""""tableName": "(\w+)",\s+"createSql": "([^"]*)"""")

        /** Столбец в CREATE TABLE: `имя` ТИП. */
        val COLUMN = Regex("""`(\w+)` (INTEGER|TEXT|REAL|BLOB)""")
    }
}
