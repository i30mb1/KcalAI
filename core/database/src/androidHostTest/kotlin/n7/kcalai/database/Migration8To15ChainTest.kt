package n7.kcalai.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Обновление из Google Play: версия 1.3 оставила базу версии 8, а приложение открывает её версией 15. Цепочка
 * миграций 8 → 15 целиком — на базе с записями во всех главных таблицах. После неё каждая таблица и каждый индекс —
 * ровно как в схеме 15 (то же сверяет Room при открытии: расхождение — падение у людей), а записанное — на месте.
 */
class Migration8To15ChainTest {

    private val connection = BundledSQLiteDriver().open(":memory:")

    @AfterTest
    fun close() = connection.close()

    @Test
    fun `база из Google Play доходит до версии 15 с дневником на месте`() {
        schemaOf(8).create().forEach(connection::execSQL)
        RECORDS.forEach(connection::execSQL)

        listOf(
            KcalDatabase_AutoMigration_8_9_Impl(),
            KcalDatabase_AutoMigration_9_10_Impl(),
            KcalDatabase_AutoMigration_10_11_Impl(),
            KcalDatabase_AutoMigration_11_12_Impl(),
            KcalDatabase_AutoMigration_12_13_Impl(),
            KcalDatabase_AutoMigration_13_14_Impl(),
            KcalDatabase_AutoMigration_14_15_Impl(),
        ).forEach { it.migrate(connection) }

        val target = schemaOf(KCAL_DB_VERSION)
        for (table in target.tables) {
            // Room сверяет столбцы по именам, порядок не важен
            assertEquals(table.columns.sortedBy { it.name }, connection.columns(table.name).sortedBy { it.name }, "столбцы ${table.name}")
            assertEquals(table.indices, connection.indices(table.name), "индексы ${table.name}")
        }
        assertEquals("Гречка отварная|200|280", connection.text("SELECT displayName || '|' || grams || '|' || fiber100 FROM diary_entry"))
        assertEquals("Сырник домашний", connection.text("SELECT name FROM user_food"))
        assertEquals("2000|120", connection.text("SELECT kcal || '|' || prot FROM daily_goal"))
        assertEquals("72500", connection.text("SELECT weightGrams FROM body_metric"))
        assertEquals("Женя", connection.text("SELECT name FROM mascot_profile"))
        assertEquals("Привет!", connection.text("SELECT text FROM mascot_message"))
        assertEquals("1", connection.text("SELECT count(*) FROM mascot_message WHERE hiddenAt IS NULL"), "новые столбцы у прежних записей пусты")
    }

    private class Table(val name: String, val columns: List<Column>, val indices: List<String>, val sql: List<String>)
    private data class Column(val name: String, val type: String, val notNull: Boolean, val pk: Int)
    private class Schema(val tables: List<Table>) {
        fun create() = tables.flatMap { it.sql }
    }

    /** Схема версии [version] из `schemas/`: таблицы, их столбцы и индексы — как их выгрузил Room. */
    private fun schemaOf(version: Int): Schema {
        val json = File("schemas/n7.kcalai.database.KcalDatabase/$version.json").readText()
        // таблица — от своего "tableName" до следующего
        val starts = ENTITY.findAll(json).toList()
        val tables = starts.mapIndexed { i, match ->
            val name = match.groupValues[1]
            val body = json.substring(match.range.first, starts.getOrNull(i + 1)?.range?.first ?: json.length)
            val create = match.groupValues[2].replace("\${TABLE_NAME}", name)
            val pk = PRIMARY.find(body)?.groupValues?.get(1).orEmpty().split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            val columns = FIELD.findAll(body.substringAfter("\"fields\": [").substringBefore("\"primaryKey\""))
                .map { f -> Column(f.groupValues[1], f.groupValues[2], f.groupValues[3] == "true", pk.indexOf(f.groupValues[1]) + 1) }
                .toList()
            val indices = INDEX.findAll(body.substringAfter("\"indices\": [", "")).map { it.groupValues[1] to it.groupValues[2] }.toList()
            Table(
                name = name,
                columns = columns,
                indices = indices.map { it.first }.sorted(),
                sql = listOf(create) + indices.map { it.second.replace("\${TABLE_NAME}", name) },
            )
        }
        return Schema(tables)
    }

    private fun SQLiteConnection.columns(table: String): List<Column> =
        prepare("PRAGMA table_info($table)").use { st ->
            buildList { while (st.step()) add(Column(st.getText(1), st.getText(2), st.getLong(3) == 1L, st.getLong(5).toInt())) }
        }

    /** Свои индексы таблицы (без автоматических sqlite_autoindex). */
    private fun SQLiteConnection.indices(table: String): List<String> =
        prepare("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' AND name NOT LIKE 'sqlite_autoindex%'").use { st ->
            buildList { while (st.step()) add(st.getText(0)) }.sorted()
        }

    private fun SQLiteConnection.text(sql: String): String =
        prepare(sql).use { st ->
            check(st.step()) { "пусто: $sql" }
            st.getText(0)
        }

    private companion object {
        val ENTITY = Regex(""""tableName": "(\w+)",\s+"createSql": "([^"]*)"""")
        val FIELD = Regex(""""columnName": "(\w+)",\s+"affinity": "(\w+)"(?:,\s+"notNull": (true|false))?""")
        val PRIMARY = Regex(""""columnNames": \[([^\]]*)\]""")
        val INDEX = Regex(""""name": "(\w+)",\s+"unique": (?:true|false),\s+"columnNames": \[[^\]]*\],\s+"orders": \[[^\]]*\],\s+"createSql": "([^"]*)"""")

        /** Записи человека, который пользовался версией 1.3: дневник, свой продукт, цель, вес, персонажи. */
        val RECORDS = listOf(
            "INSERT INTO diary_entry (dateEpochDay, meal, displayName, grams, kcal100, prot100, fat100, carb100, source, foodRef, createdAt, fiber100) " +
                "VALUES (20000, 'LUNCH', 'Гречка отварная', 200, 110, 420, 110, 2130, 'TEXT', 'generic:1', 1000, 280)",
            "INSERT INTO user_food (barcode, name, kcal100, prot100, fat100, carb100, servingG, createdAt, fiber100) " +
                "VALUES (NULL, 'Сырник домашний', 220, 1500, 900, 1800, 120, 1000, NULL)",
            "INSERT INTO daily_goal (fromDateEpochDay, kcal, prot, fat, carb, fiber) VALUES (19990, 2000, 120, 70, 230, 28)",
            "INSERT INTO body_metric (dateEpochDay, weightGrams, createdAt) VALUES (20000, 72500, 1000)",
            "INSERT INTO mascot_profile (id, name, nameAsked, sex, consent, consentAt, lastOpenAt) VALUES (1, 'Женя', 1, NULL, 'YES', 1000, 2000)",
            "INSERT INTO mascot_message (mascot, topic, text, lineIds, delivery, dateEpochDay, createdAt, openedAt, choice) " +
                "VALUES ('GRAIN', 'HELLO', 'Привет!', '', 'FEED', 20000, 1000, NULL, NULL)",
        )
    }
}
