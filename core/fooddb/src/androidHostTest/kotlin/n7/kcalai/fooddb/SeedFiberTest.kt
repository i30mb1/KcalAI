package n7.kcalai.fooddb

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import n7.kcalai.model.Nutriments

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

    /** На российской пачке клетчатку не печатают — её подсказывает тот же продукт справочника. */
    @Test
    fun `товару без клетчатки её подсказывает справочник — по названию и цифрам`() {
        val pack = Nutriments(kcal100 = 231, prot100 = 230, fat100 = 70, carb100 = 5750)
        val curd = Nutriments(kcal100 = 167, prot100 = 1480, fat100 = 820, carb100 = 850)

        assertEquals(710, db.withFiber("Чернослив без косточки", pack).fiber100)
        assertEquals(null, db.withFiber("Творог 9% с черносливом", curd).fiber100)
    }

    private companion object {
        const val BUCKWHEAT_DRY = 2L
        const val CHICKEN_BREAST = 101L
        const val APPLE = 419L
        const val WHEAT_BRAN = 2137L
    }
}
