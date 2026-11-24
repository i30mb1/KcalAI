package n7.kcalai.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Браузер пускают один раз, и ответ ложится только в его просьбу.
 *
 * Запросы взяты из `WebClientDao` дословно: проверяется тот SQL, который выполнит Room.
 */
class WebClientSqlTest {

    private val connection = BundledSQLiteDriver().open(":memory:").apply {
        createSqlOf(11).forEach(::execSQL)
        INDEX.findAll(File(SCHEMA.format(11)).readText())
            .map { it.groupValues[1] }
            .filter { "index_web_client" in it }
            .map { it.replace("\${TABLE_NAME}", "web_client") }
            .forEach(::execSQL)
    }

    @AfterTest
    fun close() = connection.close()

    @Test
    fun `ответ принимается один раз`() {
        insert(id = 1, token = "a")

        assertEquals(1, update("UPDATE web_client SET choice = 'YES' WHERE id = 1 AND choice IS NULL"))
        assertEquals(0, update("UPDATE web_client SET choice = 'NO' WHERE id = 1 AND choice IS NULL"), "второй ответ не принимается")
    }

    /** По токену браузер узнают — два одинаковых были бы одним браузером с двумя ответами. */
    @Test
    fun `токен у браузера свой`() {
        insert(id = 1, token = "a")

        assertFailsWith<Exception> { insert(id = 2, token = "a") }
    }

    private fun insert(id: Long, token: String) = connection.execSQL(
        "INSERT INTO web_client (id, token, code, label, dateEpochDay, createdAt) " +
            "VALUES ($id, '$token', '4821', 'Chrome', 20000, 1000)"
    )

    private fun update(sql: String): Int {
        connection.execSQL(sql)
        return connection.prepare("SELECT changes()").use { it.step(); it.getLong(0).toInt() }
    }

    private fun createSqlOf(version: Int): List<String> =
        CREATE_TABLE.findAll(File(SCHEMA.format(version)).readText())
            .map { match -> match.groupValues[2].replace("\${TABLE_NAME}", match.groupValues[1]) }
            .toList()

    private companion object {
        const val SCHEMA = "schemas/n7.kcalai.database.KcalDatabase/%d.json"
        val CREATE_TABLE = Regex(""""tableName": "(\w+)",\s+"createSql": "([^"]*)"""")
        val INDEX = Regex(""""createSql": "(CREATE UNIQUE INDEX[^"]*)"""")
    }
}
