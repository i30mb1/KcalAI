package n7.kcalai.repositories

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import n7.kcalai.database.EmbeddingMarkup
import n7.kcalai.database.SemanticDao
import n7.kcalai.database.TextEmbeddingEntity
import n7.kcalai.fooddb.decodeEmbedding
import n7.kcalai.fooddb.encodeEmbedding

/** Строка и то, как она размечена для модели: одна и та же строка в двух разметках — два отпечатка. */
data class EmbeddingKey(val markup: EmbeddingMarkup, val text: String)

/**
 * Отпечатки личных строк: своих продуктов и прошлых запросов.
 *
 * Считать их некому, кроме телефона, и считаются они лениво — при первом запросе,
 * который дошёл до поиска по смыслу, а не на старте: у большинства запусков такого
 * запроса нет, и будить ради них модель на 260 МБ незачем. Посчитанное лежит в Room,
 * поэтому каждая строка считается один раз за жизнь модели.
 *
 * Хранится начало вектора длиной [dim] — той же, что у отпечатков справочника:
 * пороги подобраны на ней, а на полной длине косинусы другие.
 *
 * @param model какой моделью и до какой длины посчитано; отпечатки с другим значением
 *        выбрасываются при первом обращении
 */
class EmbeddingCache(
    private val dao: SemanticDao,
    private val embedder: QueryEmbedder,
    private val model: String,
    private val dim: Int,
) {

    private val lock = Mutex()
    private var known: MutableMap<EmbeddingKey, FloatArray>? = null

    /**
     * Что из [keys] уже посчитано, плюс не больше [budget] новых.
     *
     * Человек ждёт ответ на запрос, а накопленных за месяцы строк бывают сотни, по 70 мс
     * на каждую, — поэтому долг отдаётся частями, от поиска к поиску. [keys] идут от
     * важных к неважным: то, что не поместилось в бюджет, просто не участвует в этом поиске.
     */
    suspend fun vectors(keys: List<EmbeddingKey>, budget: Int): Map<EmbeddingKey, FloatArray> = lock.withLock {
        val vectors = known ?: load().also { known = it }

        val fresh = mutableListOf<TextEmbeddingEntity>()
        for (key in keys.distinct().filterNot(vectors::containsKey).take(budget)) {
            // Модели нет — следующая строка ответит тем же, спрашивать незачем.
            val full = embed(key)?.takeIf { it.size >= dim } ?: break
            val vector = normalized(full, dim)
            vectors[key] = vector
            fresh += TextEmbeddingEntity(key.markup, key.text, model, encodeEmbedding(vector))
        }
        if (fresh.isNotEmpty()) dao.upsertEmbeddings(fresh)

        keys.mapNotNull { key -> vectors[key]?.let { key to it } }.toMap()
    }

    private suspend fun load(): MutableMap<EmbeddingKey, FloatArray> {
        dao.dropOtherModels(model)
        return dao.embeddings(model)
            .associateTo(mutableMapOf()) { EmbeddingKey(it.markup, it.text) to decodeEmbedding(it.vec) }
    }

    private suspend fun embed(key: EmbeddingKey): FloatArray? = when (key.markup) {
        EmbeddingMarkup.QUERY -> embedder.embed(key.text)
        EmbeddingMarkup.DOCUMENT -> embedder.embedDocument(key.text)
    }
}
