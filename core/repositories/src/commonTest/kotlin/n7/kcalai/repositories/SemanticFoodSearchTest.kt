package n7.kcalai.repositories

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Nutriments

class SemanticFoodSearchTest {

    private val borscht = candidate(602, "Борщ")
    private val bread = candidate(17, "Хлеб ржаной (чёрный)")
    private val rows = mapOf(602L to borscht, 17L to bread)

    private val index = SemanticIndex(
        mapOf(
            602L to floatArrayOf(1f, 0f),
            17L to floatArrayOf(0f, 1f),
        )
    )

    private var embedded = 0

    private fun search(
        vector: FloatArray?,
        index: SemanticIndex? = this.index,
    ) = SemanticFoodSearch(
        embedder = QueryEmbedder { embedded++; vector },
        index = { index },
        rows = { ids -> ids.mapNotNull(rows::get) },
    )

    @Test
    fun `опечатка находит продукт по смыслу`() = runTest {
        val found = search(floatArrayOf(0.95f, 0.31f)).search("барщ", limit = 3)

        assertEquals(borscht, found.first())
    }

    @Test
    fun `найденное по смыслу не выдаётся за точное совпадение`() = runTest {
        val found = search(floatArrayOf(1f, 0f)).search("борщец", limit = 3)

        assertFalse(found.first().exactMatch)
    }

    @Test
    fun `бессмыслица не притягивает ближайший продукт`() = runTest {
        // Косинус 0.7 у обоих — так «хрючево» похоже на всё сразу и ни на что.
        val found = search(floatArrayOf(0.7f, 0.7f)).search("хрючево", limit = 3)

        assertTrue(found.isEmpty(), "$found")
    }

    @Test
    fun `модель ещё не скачана — поиск по смыслу молчит`() = runTest {
        assertTrue(search(vector = null).search("барщ", limit = 3).isEmpty())
    }

    @Test
    fun `база без отпечатков не будит модель`() = runTest {
        // Докачанный старый seed.db таблицы не знает: грузить модель ради пустого
        // индекса — это сотня мегабайт памяти впустую.
        val found = search(floatArrayOf(1f, 0f), index = null).search("барщ", limit = 3)

        assertTrue(found.isEmpty())
        assertEquals(0, embedded)
    }

    private fun candidate(id: Long, name: String) = FoodCandidate(
        ref = FoodRef.Generic(id),
        displayName = name,
        nutriments = Nutriments(kcal100 = 50, prot100 = 100, fat100 = 100, carb100 = 100),
        servingG = 300,
    )
}
