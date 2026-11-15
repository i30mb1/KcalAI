package n7.kcalai.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Предложение соседа отвечается один раз — и ответ попадает только в него.
 *
 * На этом держится правило ленты «повторный тап ничего не запускает»: вид гасит
 * кнопки, но проверка обязана быть и в логике. Вопросы персонажей отвечаются
 * по теме и накрывают все копии разом — предложениям так нельзя: их приходит
 * несколько разных, и ответ на гречку не должен накрыть творог.
 *
 * Запросы взяты из `PeerOfferDao` дословно: проверяется тот самый SQL, который
 * выполнит Room.
 */
class PeerOfferSqlTest {

    private val connection = BundledSQLiteDriver().open(":memory:").apply {
        createSqlOf(9).forEach(::execSQL)
    }

    @AfterTest
    fun close() = connection.close()

    @Test
    fun `ответ принимается один раз`() {
        insert(id = 1, food = "Гречка")

        assertEquals(1, answer(id = 1), "первый ответ обязан пройти")
        assertEquals(0, answer(id = 1), "второй ответ не принимается")
    }

    @Test
    fun `ответ на одно предложение не трогает другое`() {
        insert(id = 1, food = "Гречка")
        insert(id = 2, food = "Творог")

        answer(id = 1)

        assertEquals("YES", choiceOf(1))
        assertEquals(null, choiceOf(2), "творог остался ждать ответа")
    }

    @Test
    fun `отвеченное остаётся в переписке`() {
        insert(id = 1, food = "Гречка")

        answer(id = 1)

        assertEquals(1, count("SELECT count(*) FROM peer_offer"), "предложение не удаляется после ответа")
        assertEquals(0, count("SELECT count(*) FROM peer_offer WHERE choice IS NULL"))
    }

    private fun insert(id: Long, food: String) = connection.execSQL(
        "INSERT INTO peer_offer (id, fromName, displayName, grams, kcal100, prot100, fat100, carb100, " +
            "fiber100, gtin, dateEpochDay, createdAt, choice) " +
            "VALUES ($id, 'Аня', '$food', 200, 110, 420, 110, 2130, NULL, NULL, 20000, 1000, NULL)"
    )

    /** Тот же запрос, что у `PeerOfferDao.answer`. */
    private fun answer(id: Long): Int {
        connection.execSQL("UPDATE peer_offer SET choice = 'YES' WHERE id = $id AND choice IS NULL")
        return count("SELECT changes()")
    }

    private fun choiceOf(id: Long): String? =
        connection.prepare("SELECT choice FROM peer_offer WHERE id = $id").use { statement ->
            check(statement.step())
            if (statement.isNull(0)) null else statement.getText(0)
        }

    private fun count(sql: String): Int =
        connection.prepare(sql).use { statement ->
            check(statement.step())
            statement.getInt(0)
        }

    private fun createSqlOf(version: Int): List<String> =
        CREATE_TABLE.findAll(File("schemas/n7.kcalai.database.KcalDatabase/$version.json").readText())
            .map { match -> match.groupValues[2].replace("\${TABLE_NAME}", match.groupValues[1]) }
            .toList()

    private companion object {
        val CREATE_TABLE = Regex(""""tableName": "(\w+)",\s+"createSql": "([^"]*)"""")
    }
}
