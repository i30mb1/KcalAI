package n7.kcalai.repositories

import kotlin.math.sqrt
import n7.kcalai.model.FoodCandidate

/**
 * Текст запроса -> отпечаток той же модели, которой посчитаны отпечатки справочника.
 *
 * `null` — модели на телефоне нет: Play ещё не докачал пак или телефон его не тянет.
 * Это штатное состояние, а не ошибка.
 */
fun interface QueryEmbedder {
    suspend fun embed(text: String): FloatArray?
}

data class ScoredId(val id: Long, val score: Float)

/**
 * Отпечатки продуктов и поиск ближайших по косинусу.
 *
 * Продуктов сотни, поэтому перебор: дерево поиска окупилось бы на сотнях тысяч.
 * Отпечатки базы уже приведены к длине 1, и косинус — это просто скалярное произведение.
 */
class SemanticIndex(private val vectors: Map<Long, FloatArray>) {

    private val dim = vectors.values.firstOrNull()?.size ?: 0

    val isEmpty: Boolean get() = vectors.isEmpty()

    fun nearest(query: FloatArray, limit: Int, minScore: Float): List<ScoredId> {
        if (dim == 0 || query.size < dim) return emptyList()
        val q = normalized(query, dim)
        return vectors.entries
            .map { (id, v) -> ScoredId(id, dot(q, v)) }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)
    }
}

/**
 * Поиск по смыслу: «барщ», «кофеёк с молочком», «оливьешка».
 *
 * Только запасной путь — когда обычный поиск по словам не нашёл ничего. Слова
 * точнее: «гречка» обязана найти гречку отварную, а у отпечатков гречка отварная,
 * с грибами и сухая крупа почти равны. Найденное здесь всегда помечено как
 * неточное совпадение, и человек выбирает из вариантов тапом.
 */
class SemanticFoodSearch(
    private val embedder: QueryEmbedder,
    private val index: suspend () -> SemanticIndex?,
    private val rows: suspend (ids: List<Long>) -> List<FoodCandidate>,
) {

    suspend fun search(query: String, limit: Int): List<FoodCandidate> {
        val index = index()?.takeUnless { it.isEmpty } ?: return emptyList()
        val vector = embedder.embed(query) ?: return emptyList()
        val ids = index.nearest(vector, limit, MIN_SCORE).map { it.id }
        return rows(ids).map { it.copy(exactMatch = false) }
    }

    private companion object {
        /**
         * Порог похожести. Подобран на EmbeddingGemma 2 270M по нашей базе:
         * бессмыслица («хрючево», «привет как дела») даёт 0.66–0.72,
         * а слабые, но верные совпадения («ложка мёда» -> Мёд) — от 0.73.
         */
        const val MIN_SCORE = 0.73f
    }
}

private fun normalized(v: FloatArray, dim: Int): FloatArray {
    var sum = 0.0
    for (i in 0 until dim) sum += v[i] * v[i]
    val norm = sqrt(sum).toFloat()
    return FloatArray(dim) { if (norm == 0f) 0f else v[it] / norm }
}

private fun dot(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}
