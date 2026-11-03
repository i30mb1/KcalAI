package n7.kcalai.repositories

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import n7.kcalai.database.PastPickRow
import n7.kcalai.database.SemanticDao
import n7.kcalai.database.TextEmbeddingEntity

/** Кэш отпечатков в памяти: проверяется логика кэша, а не SQL. */
internal class FakeSemanticDao : SemanticDao {

    val rows = mutableListOf<TextEmbeddingEntity>()
    var picks: List<PastPickRow> = emptyList()

    override suspend fun embeddings(model: String): List<TextEmbeddingEntity> = rows.filter { it.model == model }

    override suspend fun upsertEmbeddings(rows: List<TextEmbeddingEntity>) {
        for (row in rows) {
            this.rows.removeAll { it.markup == row.markup && it.text == row.text }
            this.rows += row
        }
    }

    override suspend fun dropOtherModels(model: String) {
        rows.removeAll { it.model != model }
    }

    override suspend fun pastPicks(limit: Int): List<PastPickRow> = picks.take(limit)
}

/**
 * Модель с заранее известными ответами. Незнакомый текст — `null`, как у модели,
 * которой на телефоне нет.
 */
internal class FakeEmbedder(
    private val queries: Map<String, FloatArray> = emptyMap(),
    private val documents: Map<String, FloatArray> = emptyMap(),
) : QueryEmbedder {

    /** «q:текст» и «d:текст» в порядке вызовов. */
    val calls = mutableListOf<String>()

    override suspend fun embed(text: String): FloatArray? {
        calls += "q:$text"
        return queries[text]
    }

    override suspend fun embedDocument(text: String): FloatArray? {
        calls += "d:$text"
        return documents[text]
    }
}

/** Единичный вектор на плоскости: косинус между двумя такими — косинус разности углов. */
internal fun at(degrees: Double): FloatArray {
    val radians = degrees * PI / 180
    return floatArrayOf(cos(radians).toFloat(), sin(radians).toFloat())
}
