package n7.kcalai.fooddb

import kotlin.test.Test
import kotlin.test.assertEquals
import n7.kcalai.model.Nutriments

/**
 * Клетчатку товара, у которого её нет, подсказывает справочник: тот же продукт
 * по названию и по цифрам. Чернослив с пачки — тот же чернослив, а творог
 * с черносливом — уже нет.
 */
class FiberEstimateTest {

    private val prunes = GenericRow(
        id = 1535,
        nameKey = "prunes_dried",
        nameRu = "Чернослив",
        nutriments = Nutriments(kcal100 = 240, prot100 = 218, fat100 = 38, carb100 = 6388, fiber100 = 710),
        defaultPortionG = 40,
    )
    private val directory = mapOf("чернослив" to prunes)

    /** Цифры с российской пачки чернослива: клетчатку на ней не печатают. */
    private val packOfPrunes = Nutriments(kcal100 = 231, prot100 = 230, fat100 = 70, carb100 = 5750)

    private fun estimate(name: String, nutriments: Nutriments): Nutriments =
        estimateFiber(name, nutriments) { phrases -> directory.filterKeys { it in phrases } }

    @Test
    fun `у чернослива с пачки клетчатка берётся из справочника`() {
        assertEquals(710, estimate("Чернослив без косточки", packOfPrunes).fiber100)
    }

    @Test
    fun `цифры не сходятся — это другой продукт, клетчатка остаётся неизвестной`() {
        val curd = Nutriments(kcal100 = 167, prot100 = 1480, fat100 = 820, carb100 = 850)

        assertEquals(null, estimate("Чернослив в твороге", curd).fiber100)
    }

    @Test
    fun `слово в середине названия не считается — продукт называют с начала`() {
        assertEquals(null, estimate("Батончик чернослив", packOfPrunes).fiber100)
    }

    @Test
    fun `нулю не верим, когда товар назван ровно как продукт с клетчаткой`() {
        assertEquals(710, estimate("Чернослив", packOfPrunes.copy(fiber100 = 0)).fiber100)
    }

    @Test
    fun `ноль остаётся нулём, если совпало только начало названия`() {
        assertEquals(0, estimate("Чернослив без косточки", packOfPrunes.copy(fiber100 = 0)).fiber100)
    }

    @Test
    fun `клетчатку с этикетки справочник не трогает и в базу не ходит`() {
        var asked = false

        val result = estimateFiber("Чернослив", packOfPrunes.copy(fiber100 = 650)) {
            asked = true
            directory
        }

        assertEquals(650, result.fiber100)
        assertEquals(false, asked)
    }

    @Test
    fun `справочник спрашивают про название целиком и про его начало`() {
        var asked = emptyList<String>()

        estimateFiber("Чернослив сушёный, без косточек Premium 200", packOfPrunes) { phrases ->
            asked = phrases
            emptyMap()
        }

        assertEquals(
            listOf(
                "чернослив сушеный, без косточек premium 200",
                "чернослив сушеный без косточек premium 200",
                "чернослив сушеный без косточек",
                "чернослив сушеный без",
                "чернослив сушеный",
                "чернослив",
            ),
            asked,
        )
    }
}
