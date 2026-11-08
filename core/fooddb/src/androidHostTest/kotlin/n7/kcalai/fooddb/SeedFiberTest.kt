package n7.kcalai.fooddb

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Справочник, который едет в приложении, знает клетчатку каждого продукта.
 *
 * Проверяется собранный файл из assets, а не сборщик: именно его откроет телефон,
 * и колонка, забытая в запросе или в сборке, всплыла бы только там.
 */
class SeedFiberTest {

    private val db = FoodDbFactory.open(BundledSQLiteDriver(), seedPath = "../../app/src/main/assets/seed.db", foodPath = null)

    @AfterTest
    fun close() = db.close()

    @Test
    fun `у продуктов справочника клетчатка известна — у мяса это ноль`() {
        val fiber = db.genericsByIds(listOf(BUCKWHEAT_DRY, CHICKEN_BREAST, APPLE, WHEAT_BRAN))
            .associate { it.id to it.nutriments.fiber100 }

        assertEquals(
            mapOf(BUCKWHEAT_DRY to 1130, CHICKEN_BREAST to 0, APPLE to 240, WHEAT_BRAN to 4300),
            fiber,
        )
    }

    private companion object {
        const val BUCKWHEAT_DRY = 2L
        const val CHICKEN_BREAST = 101L
        const val APPLE = 419L
        const val WHEAT_BRAN = 2137L
    }
}
