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
    fun `товар с белорусским кодом находится, даже если в OFF у него не отмечена страна`() {
        // В Open Food Facts паста отмечена только США: страну ставит тот, кто сканировал.
        // Префикс кода (481 — Беларусь) говорит о полке надёжнее.
        val paste = db.findByBarcode("4810268023323")!!

        assertEquals("Творожная паста", paste.name)
        assertEquals("Савушкин", paste.brand)
        assertEquals(Nutriments(kcal100 = 112, prot100 = 700, fat100 = 350, carb100 = 1300), paste.nutriments)
    }

    /** Проверенное и опубликованное в панели сервера едет в справочник: у полки сеть не нужна. */
    @Test
    fun `опубликованный на сервере товар находится без сети`() {
        val pineapple = db.findByBarcode("4814944008796")!!

        assertEquals("Ананасы кусочки", pineapple.name)
        assertEquals(55, pineapple.nutriments.kcal100)
    }

    @Test
    fun `товары ищутся и по бренду`() {
        assertTrue(db.searchProducts("простоквашино", limit = 5).isNotEmpty())
    }
}
