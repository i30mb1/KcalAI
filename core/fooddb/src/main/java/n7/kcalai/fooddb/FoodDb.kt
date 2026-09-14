package n7.kcalai.fooddb

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import n7.kcalai.model.Nutriments

/**
 * Доступ к справочным базам. `seed.db` открыт как `main`, докачанный `food.db`
 * подключается как схема `food`.
 *
 * Соединение одно и не потокобезопасно — вызывать с одного диспетчера (IO).
 */
class FoodDb internal constructor(
    private val connection: SQLiteConnection,
    private val queries: FoodDbQueries,
) : AutoCloseable {

    fun findByBarcode(gtin: String): ProductRow? =
        connection.prepare(queries.findByBarcode).use { stmt ->
            stmt.bindText(1, gtin)
            if (stmt.step()) stmt.readProduct() else null
        }

    fun searchProducts(query: String, limit: Int): List<ProductRow> {
        val match = ftsPrefixQuery(query) ?: return emptyList()
        return connection.prepare(queries.searchProducts).use { stmt ->
            stmt.bindText(1, match)
            stmt.bindLong(2, limit.toLong())
            buildList { while (stmt.step()) add(stmt.readProduct()) }
        }
    }

    /**
     * Поиск по генерик-таблице: сначала точные совпадения алиаса целиком, потом
     * префиксный поиск по основам. Точные идут первыми и помечены [GenericRow.exactMatch] —
     * по этому флагу парсер поднимает confidence до автоподстановки.
     */
    fun searchGeneric(query: String, limit: Int): List<GenericRow> {
        val normalized = normalizeForSearch(query)
        if (normalized.isEmpty()) return emptyList()

        val exact = connection.prepare(FoodDbQueries.EXACT_ALIAS).use { stmt ->
            stmt.bindText(1, normalized)
            stmt.bindLong(2, limit.toLong())
            buildList { while (stmt.step()) add(stmt.readGeneric(exactMatch = true)) }
        }
        if (exact.size >= limit) return exact.take(limit)

        val match = ftsPrefixQuery(normalized) ?: return exact
        val seen = exact.mapTo(mutableSetOf()) { it.id }
        val fuzzy = connection.prepare(FoodDbQueries.SEARCH_FTS).use { stmt ->
            stmt.bindText(1, match)
            // Берём с запасом: часть строк отсеется дедупом по точным совпадениям.
            stmt.bindLong(2, (limit + exact.size).toLong())
            buildList {
                while (stmt.step()) {
                    val row = stmt.readGeneric(exactMatch = false)
                    if (seen.add(row.id)) add(row)
                }
            }
        }
        return (exact + fuzzy).take(limit)
    }

    /**
     * Все порционные единицы продукта разом.
     *
     * Парсер всё равно смотрит на несколько единиц сразу (есть ли «шт», чтобы понять,
     * штучный ли продукт), поэтому выборка по одной единице только добавляла бы
     * обращений к базе.
     */
    fun portionUnits(genericId: Long): Map<String, Int> =
        connection.prepare(FoodDbQueries.PORTION_UNITS).use { stmt ->
            stmt.bindLong(1, genericId)
            buildMap { while (stmt.step()) put(stmt.getText(0), stmt.getInt(1)) }
        }

    override fun close() {
        connection.close()
    }
}

private fun SQLiteStatement.readProduct(): ProductRow =
    ProductRow(
        barcode = getText(0),
        name = getText(1),
        brand = if (isNull(2)) null else getText(2),
        nutriments = Nutriments(
            kcal100 = getInt(3),
            prot100 = getInt(4),
            fat100 = getInt(5),
            carb100 = getInt(6),
        ),
        servingG = if (isNull(7)) null else getInt(7),
    )

private fun SQLiteStatement.readGeneric(exactMatch: Boolean): GenericRow =
    GenericRow(
        id = getLong(0),
        nameKey = getText(1),
        nameRu = getText(2),
        nutriments = Nutriments(
            kcal100 = getInt(3),
            prot100 = getInt(4),
            fat100 = getInt(5),
            carb100 = getInt(6),
        ),
        defaultPortionG = getInt(7),
        exactMatch = exactMatch,
    )

object FoodDbFactory {

    /**
     * @param seedPath путь к seed.db (обязателен, лежит в filesDir после копирования из assets)
     * @param foodPath путь к докачанному food.db, либо null, если полная база ещё не скачана
     */
    fun open(driver: SQLiteDriver, seedPath: String, foodPath: String?): FoodDb {
        val connection = driver.open(seedPath)
        if (foodPath != null) {
            connection.prepare("ATTACH DATABASE ? AS food").use { stmt ->
                stmt.bindText(1, foodPath)
                stmt.step()
            }
        }
        connection.execSQL("PRAGMA query_only = ON")
        return FoodDb(connection, FoodDbQueries(hasFullDb = foodPath != null))
    }
}
