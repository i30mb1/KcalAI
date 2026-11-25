package n7.kcalai.feature.diary

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import n7.kcalai.fooddb.FoodDbFactory
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductOrigin
import n7.kcalai.repositories.BarcodeLookup
import n7.kcalai.repositories.FoodDbSource
import n7.kcalai.repositories.FoodRepository
import n7.kcalai.repositories.RemoteProduct
import n7.kcalai.repositories.RemoteProductSource

/**
 * Отсканированный товар приходит без клетчатки — на пачке её не печатают.
 * Справочник знает тот же продукт и подсказывает её, а в кэше остаётся то,
 * что сказала сеть: правило подбора может поменяться, ответ сети — нет.
 */
class ScannedFiberTest {

    private val dispatcher = StandardTestDispatcher()
    private val cache = FakeCachedProductDao()

    private val food = FoodRepository(
        foodDb = FoodDbSource { FoodDbFactory.open(PrunesDriver, seedPath = "prunes", foodPath = null) },
        userFoodDao = FakeUserFoodDao(),
        cachedProductDao = cache,
        contributionDao = FakeContributionDao(),
        remote = RemoteProductSource { gtin ->
            RemoteProduct(
                gtin = gtin,
                name = "Чернослив без косточки",
                brand = "Семушка",
                nutriments = Nutriments(kcal100 = 231, prot100 = 230, fat100 = 70, carb100 = 5750),
                servingG = null,
                origin = ProductOrigin.OFF,
            )
        },
        dispatcher = dispatcher,
    )

    @Test
    fun `чернослив с пачки без клетчатки получает её из справочника`() = runTest(dispatcher) {
        val found = food.byBarcode(GTIN) as BarcodeLookup.Found

        assertEquals(710, found.candidate.nutriments.fiber100)
    }

    @Test
    fun `повторный скан из кэша подсказывает клетчатку так же, а в кэше её нет`() = runTest(dispatcher) {
        food.byBarcode(GTIN)

        val again = food.byBarcode(GTIN) as BarcodeLookup.Found

        assertEquals(710, again.candidate.nutriments.fiber100)
        assertEquals(null, cache.findByGtin(GTIN)!!.fiber100)
    }

    private companion object {
        const val GTIN = "4607012345678"
    }
}

/** Справочник из одного продукта: на вопрос про алиасы отвечает черносливом, товаров не знает. */
private object PrunesDriver : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection = object : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement =
            RowsStatement(if ("a.alias IN" in sql) listOf(PRUNES) else emptyList())

        override fun close() = Unit
    }

    /** Колонки генерика в порядке запроса, последней — совпавший алиас. */
    private val PRUNES: List<Any> = listOf(1535L, "prunes_dried", "Чернослив", 240L, 218L, 38L, 6388L, 40L, 710L, "чернослив")
}

private class RowsStatement(private val rows: List<List<Any>>) : SQLiteStatement {
    private var at = -1

    override fun bindBlob(index: Int, value: ByteArray) = Unit
    override fun bindDouble(index: Int, value: Double) = Unit
    override fun bindLong(index: Int, value: Long) = Unit
    override fun bindText(index: Int, value: String) = Unit
    override fun bindNull(index: Int) = Unit
    override fun getBlob(index: Int): ByteArray = error("блобов нет")
    override fun getDouble(index: Int): Double = error("дробных нет")
    override fun getLong(index: Int): Long = rows[at][index] as Long
    override fun getText(index: Int): String = rows[at][index] as String
    override fun isNull(index: Int): Boolean = false
    override fun getColumnCount(): Int = rows.firstOrNull()?.size ?: 0
    override fun getColumnName(index: Int): String = error("имён колонок нет")
    override fun getColumnType(index: Int): Int = error("типов колонок нет")
    override fun step(): Boolean = ++at < rows.size
    override fun reset() { at = -1 }
    override fun clearBindings() = Unit
    override fun close() = Unit
}
