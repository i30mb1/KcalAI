package n7.kcalai.repositories

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticIndexTest {

    private fun unit(vararg xs: Float): FloatArray {
        val n = sqrt(xs.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(xs.size) { xs[it] / n }
    }

    private val index = SemanticIndex(
        mapOf(
            1L to unit(1f, 0f, 0f),
            2L to unit(0.9f, 0.1f, 0f),
            3L to unit(0f, 1f, 0f),
        )
    )

    @Test
    fun `ближайшие по смыслу идут первыми`() {
        val found = index.nearest(unit(1f, 0.05f, 0f), limit = 3, minScore = 0f)

        assertEquals(listOf(1L, 2L, 3L), found.map { it.id })
    }

    @Test
    fun `ниже порога — не находка а шум`() {
        val found = index.nearest(unit(1f, 0f, 0f), limit = 3, minScore = 0.5f)

        assertEquals(listOf(1L, 2L), found.map { it.id })
    }

    @Test
    fun `запрос длиннее отпечатков базы укорачивается до их длины`() {
        // Модель отдаёт 768 чисел, в базе хранятся первые 256: у такой модели начало
        // вектора — уже рабочий отпечаток, но его надо заново привести к длине 1.
        val query = floatArrayOf(3f, 0f, 0f, 4f)

        val found = index.nearest(query, limit = 1, minScore = 0f).single()

        assertEquals(1L, found.id)
        assertTrue(found.score > 0.99f, "score ${found.score}")
    }

    @Test
    fun `пустой индекс ничего не находит`() {
        assertTrue(SemanticIndex(emptyMap()).nearest(unit(1f, 0f, 0f), limit = 3, minScore = 0f).isEmpty())
    }

    @Test
    fun `похожесть на один продукт обрезает запрос до длины индекса`() {
        val index = SemanticIndex(mapOf(1L to floatArrayOf(1f, 0f)))

        assertEquals(0.6f, index.score(floatArrayOf(3f, 4f, 100f), 1L))
        assertEquals(null, index.score(floatArrayOf(1f, 0f), 2L))
    }
}
