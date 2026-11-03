package n7.kcalai.repositories

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import n7.kcalai.database.EmbeddingMarkup

class EmbeddingCacheTest {

    private val dao = FakeSemanticDao()
    private val milk = EmbeddingKey(EmbeddingMarkup.DOCUMENT, "Молоко 3,2%")
    private val porridge = EmbeddingKey(EmbeddingMarkup.QUERY, "каша утром")
    private val coffee = EmbeddingKey(EmbeddingMarkup.QUERY, "кофе")

    private val embedder = FakeEmbedder(
        queries = mapOf("каша утром" to at(10.0), "кофе" to at(80.0), "Молоко 3,2%" to at(45.0)),
        documents = mapOf("Молоко 3,2%" to at(30.0)),
    )

    private fun cache(model: String = "m1", embedder: QueryEmbedder = this.embedder) =
        EmbeddingCache(dao, embedder, model = model, dim = 2)

    @Test
    fun `посчитанное однажды не считается снова`() = runTest {
        val cache = cache()
        cache.vectors(listOf(porridge), budget = 10)
        val again = cache.vectors(listOf(porridge), budget = 10)

        assertEquals(listOf("q:каша утром"), embedder.calls)
        assertSameVector(at(10.0), again.getValue(porridge))
    }

    @Test
    fun `отпечатки переживают перезапуск`() = runTest {
        cache().vectors(listOf(porridge), budget = 10)
        embedder.calls.clear()

        val restored = cache().vectors(listOf(porridge), budget = 10)

        assertTrue(embedder.calls.isEmpty(), "${embedder.calls}")
        assertSameVector(at(10.0), restored.getValue(porridge))
    }

    @Test
    fun `сменилась модель — старые отпечатки выброшены и посчитаны заново`() = runTest {
        cache(model = "m1").vectors(listOf(porridge), budget = 10)
        embedder.calls.clear()

        cache(model = "m2").vectors(listOf(porridge), budget = 10)

        assertEquals(listOf("q:каша утром"), embedder.calls)
        assertEquals(listOf("m2"), dao.rows.map { it.model })
    }

    @Test
    fun `за раз досчитывается не больше бюджета, начиная с первых`() = runTest {
        val cache = cache()

        val first = cache.vectors(listOf(porridge, milk, coffee), budget = 2)
        val second = cache.vectors(listOf(porridge, milk, coffee), budget = 2)

        assertEquals(setOf(porridge, milk), first.keys)
        assertEquals(setOf(porridge, milk, coffee), second.keys)
    }

    @Test
    fun `модели нет — не пишется ничего и дальше не просится`() = runTest {
        val absent = FakeEmbedder()

        val found = cache(embedder = absent).vectors(listOf(porridge, coffee), budget = 10)

        assertTrue(found.isEmpty())
        assertTrue(dao.rows.isEmpty())
        // Сотня строк — сотня бесполезных обращений к модели, которой нет.
        assertEquals(1, absent.calls.size)
    }

    @Test
    fun `продукт размечается документом, запрос — запросом`() = runTest {
        val asQuery = EmbeddingKey(EmbeddingMarkup.QUERY, "Молоко 3,2%")

        val found = cache().vectors(listOf(milk, asQuery), budget = 10)

        assertSameVector(at(30.0), found.getValue(milk))
        assertSameVector(at(45.0), found.getValue(asQuery))
    }

    @Test
    fun `хранится начало вектора, снова длиной один`() = runTest {
        val long = FakeEmbedder(queries = mapOf("каша утром" to floatArrayOf(3f, 4f, 12f)))

        val found = cache(embedder = long).vectors(listOf(porridge), budget = 10)

        assertSameVector(floatArrayOf(0.6f, 0.8f), found.getValue(porridge))
    }

    /** Приведение к длине 1 трогает младшие биты: сравнивать поразрядно здесь нечего. */
    private fun assertSameVector(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], absoluteTolerance = 1e-6f) }
    }
}
