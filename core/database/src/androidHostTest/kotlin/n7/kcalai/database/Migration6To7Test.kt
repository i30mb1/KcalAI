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
 * Обновление с версии 6 добавляет сообщениям персонажей ответ на вопрос и не трогает
 * ни дневник, ни уже сказанное.
 *
 * База собирается настоящим SQLite по выгруженной схеме прошлой версии — тем, что
 * лежит на телефонах людей, — и по ней проходит сгенерированная миграция.
 */
class Migration6To7Test {

    private val connection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    @Test
    fun `сказанное на месте, ответа у него нет, таблица совпадает со схемой версии 7`() {
        createSqlOf(6).forEach(connection::execSQL)
        connection.execSQL(
            "INSERT INTO diary_entry (dateEpochDay, meal, displayName, grams, kcal100, prot100, fat100, " +
                "carb100, source, foodRef, createdAt) " +
                "VALUES (20000, 'LUNCH', 'Гречка отварная', 200, 110, 420, 110, 2130, 'TEXT', 'generic:7', 1000)"
        )
        connection.execSQL(
            "INSERT INTO mascot_message (mascot, topic, text, lineIds, delivery, dateEpochDay, createdAt) " +
                "VALUES ('EGG', 'NAME_THANKS', 'Приятно познакомиться!', '', 'FEED', 20000, 2000)"
        )

        KcalDatabase_AutoMigration_6_7_Impl().migrate(connection)

        assertEquals("Гречка отварная", connection.text("SELECT displayName FROM diary_entry"))
        assertEquals("Приятно познакомиться!", connection.text("SELECT text FROM mascot_message"))
        assertTrue(connection.isNull("SELECT choice FROM mascot_message"), "у старого сообщения ответа нет")
        // Столбцы, а не текст CREATE: ALTER TABLE дописывает столбец своими словами.
        assertEquals(
            COLUMN.findAll(createSqlOf(7).single { "`mascot_message`" in it }).map { it.groupValues[1] to it.groupValues[2] }.toList(),
            connection.columns("mascot_message"),
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

    private fun SQLiteConnection.isNull(sql: String): Boolean =
        prepare(sql).use { statement ->
            check(statement.step()) { "пусто: $sql" }
            statement.isNull(0)
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

        /** Столбец в CREATE TABLE: `имя` ТИП. */
        val COLUMN = Regex("""`(\w+)` (INTEGER|TEXT|REAL|BLOB)""")
    }
}
