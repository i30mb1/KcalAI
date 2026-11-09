package n7.kcalai.fooddb

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import n7.kcalai.model.Nutriments

/**
 * Справочник в приложении знает товары российских полок по штрих-коду — без сети.
 *
 * У полки сети обычно нет, а сетевой запрос в Open Food Facts — секунды. Срез OFF
 * едет в seed.db, и код находится мгновенно и офлайн. Проверяется собранный файл
 * из assets: именно его откроет телефон.
 */
class SeedProductsTest {

    private val db = FoodDbFactory.open(BundledSQLiteDriver(), seedPath = "../../app/src/main/assets/seed.db", foodPath = null)

    @AfterTest
    fun close() = db.close()

    @Test
    fun `товар с российской полки находится по коду вместе с клетчаткой`() {
        val bread = db.findByBarcode("4605829006040")!!

        assertEquals("Хлеб пшеничный", bread.name)
        assertEquals("Harrys", bread.brand)
        assertEquals(Nutriments(kcal100 = 278, prot100 = 800, fat100 = 600, carb100 = 4800, fiber100 = 260), bread.nutriments)
    }

    @Test
    fun `товары ищутся и по бренду`() {
        assertTrue(db.searchProducts("простоквашино", limit = 5).isNotEmpty())
    }
}
