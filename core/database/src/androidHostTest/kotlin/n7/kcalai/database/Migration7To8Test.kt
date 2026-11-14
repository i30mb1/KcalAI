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
 * Обновление с версии 7 добавляет клетчатку записям дневника, своим и скачанным
 * продуктам и цели — и не трогает того, что уже записано.
 *
 * Клетчатка у старых строк пустая, а не ноль: у вчерашней гречки она неизвестна,
 * и «0 г» было бы неправдой. База собирается настоящим SQLite по выгруженной
 * схеме прошлой версии, и по ней проходит сгенерированная миграция.
 */
class Migration7To8Test {

    private val connection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    @Test
    fun `записанное на месте клетчатка у него неизвестна таблицы совпадают со схемой версии 8`() {
        createSqlOf(7).forEach(connection::execSQL)
        connection.execSQL(
            "INSERT INTO diary_entry (dateEpochDay, meal, displayName, grams, kcal100, prot100, fat100, " +
                "carb100, source, foodRef, createdAt) " +
                "VALUES (20000, 'LUNCH', 'Гречка отварная', 200, 110, 420, 110, 2130, 'TEXT', 'generic:1', 1000)"
        )
        connection.execSQL(
            "INSERT INTO user_food (barcode, name, kcal100, prot100, fat100, carb100, servingG, createdAt) " +
                "VALUES ('4600000000017', 'Хлебцы ржаные', 310, 1100, 200, 5700, 30, 1000)"
        )
        connection.execSQL(
            "INSERT INTO cached_product (gtin, name, brand, kcal100, prot100, fat100, carb100, servingG, origin, fetchedAt) " +
                "VALUES ('4600699500001', 'Молоко 3,2%', NULL, 59, 290, 320, 470, 250, 'OFF', 1000)"
        )
        connection.execSQL("INSERT INTO daily_goal (fromDateEpochDay, kcal, prot, fat, carb) VALUES (20000, 2100, 120, 70, 240)")

        KcalDatabase_AutoMigration_7_8_Impl().migrate(connection)

        assertEquals("Гречка отварная", connection.text("SELECT displayName FROM diary_entry"))
        assertEquals("2100", connection.text("SELECT kcal FROM daily_goal"))
        listOf(
            "SELECT fiber100 FROM diary_entry",
            "SELECT fiber100 FROM user_food",
            "SELECT fiber100 FROM cached_product",
            "SELECT fiber FROM daily_goal",
        ).forEach { sql -> assertTrue(connection.isNull(sql), "у старой строки клетчатка неизвестна: $sql") }

        // Столбцы, а не текст CREATE: ALTER TABLE дописывает столбец своими словами.
        listOf("diary_entry", "user_food", "cached_product", "daily_goal").forEach { table ->
            assertEquals(
                COLUMN.findAll(createSqlOf(8).single { "`$table`" in it }).map { it.groupValues[1] to it.groupValues[2] }.toList(),
                connection.columns(table),
                table,
            )
        }
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
