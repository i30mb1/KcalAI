package n7.kcalai.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Обновление с версии 13 добавляет сообщениям персонажей отметку «скрыто»: сказанное раньше — на месте. */
class Migration13To14Test {

    private val connection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    @Test
    fun `сказанное раньше на месте и не скрыто`() {
        createSqlOf(13).forEach(connection::execSQL)
        connection.execSQL(
            "INSERT INTO mascot_message (mascot, topic, text, lineIds, delivery, dateEpochDay, createdAt) " +
                "VALUES ('GRAIN', 'WALK_DONE', 'Прогулка в копилке', '', 'FEED', 20000, 1000)"
        )

        KcalDatabase_AutoMigration_13_14_Impl().migrate(connection)

        assertEquals("Прогулка в копилке", connection.text("SELECT text FROM mascot_message"))
        assertEquals("1", connection.text("SELECT COUNT(*) FROM mascot_message WHERE hiddenAt IS NULL"))
        assertEquals(
            COLUMN.findAll(createSqlOf(14).single { "`mascot_message`" in it }).map { it.groupValues[1] to it.groupValues[2] }.toList(),
            connection.columns("mascot_message"),
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
