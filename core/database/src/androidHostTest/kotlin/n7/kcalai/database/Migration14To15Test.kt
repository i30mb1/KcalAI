package n7.kcalai.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Обновление с версии 14 добавляет просьбам браузеров и предложениям соседа отметку «скрыто»: прежние — на месте. */
class Migration14To15Test {

    private val connection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    @Test
    fun `просьбы браузеров на месте и не скрыты`() {
        createSqlOf(14).forEach(connection::execSQL)
        connection.execSQL(
            "INSERT INTO web_client (token, code, label, dateEpochDay, createdAt, choice) " +
                "VALUES ('t-1', '4055', 'Chrome на Windows', 20782, 1000, 'YES')"
        )

        KcalDatabase_AutoMigration_14_15_Impl().migrate(connection)

        assertEquals("Chrome на Windows", connection.text("SELECT label FROM web_client"))
        assertEquals("1", connection.text("SELECT COUNT(*) FROM web_client WHERE hiddenAt IS NULL"))
        for (table in listOf("web_client", "peer_offer")) {
            assertEquals(
                COLUMN.findAll(createSqlOf(15).single { "`$table`" in it }).map { it.groupValues[1] to it.groupValues[2] }.toList(),
                connection.columns(table),
            )
        }
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
