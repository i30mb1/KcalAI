package n7.kcalai.semantic

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import n7.kcalai.model.ModelDelivery
import n7.kcalai.repositories.QueryEmbedder

/**
 * Отпечатки запросов и своих продуктов — EmbeddingGemma 2 270M через LiteRT-LM, на процессоре.
 *
 * Видеокарта здесь не нужна: один отпечаток — десятки миллисекунд, а её запуск
 * дороже самого расчёта. Движок поднимается при первом запросе, который обычный
 * поиск не осилил, и закрывается после двух минут простоя, см. [IdleHolder].
 */
class LiteRtQueryEmbedder(context: Context) : QueryEmbedder {

    private val model = EmbeddingModel(context.applicationContext)
    private val cacheDir = context.cacheDir.path

    /** Движок не потокобезопасен: все обращения и закрытие по таймеру — по одному. */
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val engine = IdleHolder(CoroutineScope(SupervisorJob() + dispatcher), IDLE_MILLIS, ::open)
    private var broken = false

    /** Докачка модели — для реплик персонажей. Подписка заодно просит пак у Play. */
    val delivery: Flow<ModelDelivery> get() = model.delivery()

    override suspend fun embed(text: String): FloatArray? = compute(QUERY_PREFIX + text)

    override suspend fun embedDocument(text: String): FloatArray? = compute(DOCUMENT_PREFIX + text)

    private suspend fun compute(input: String): FloatArray? = withContext(dispatcher) {
        try {
            engine.use { it.computeEmbedding(listOf(InputData.Text(input)), OPTIONS).embedding }
        } catch (error: Exception) {
            Log.w(TAG, "отпечаток не посчитан", error)
            null
        }
    }

    private fun open(): EmbeddingEngine? {
        if (broken) return null
        // Модели может не быть ещё: пак докачается — следующий запрос её найдёт.
        val file = model.locate() ?: return null
        return try {
            EmbeddingEngine(EmbeddingEngineConfig(modelPath = file.path, backend = Backend.CPU(), cacheDir = cacheDir))
                .also { it.initialize() }
        } catch (error: Throwable) {
            // UnsatisfiedLinkError — это Error, а не Exception: ловим всё. Сломанный
            // движок повторно не открываем — поиск по словам работает и без него.
            Log.w(TAG, "движок поиска по смыслу не поднялся", error)
            broken = true
            null
        }
    }

    private companion object {
        const val TAG = "LiteRtQueryEmbedder"

        /**
         * Разметка запроса для EmbeddingGemma. Продукты в tools/embeddb размечены как
         * документы («title: none | text: …») — разная разметка и есть то, что учит
         * модель отличать вопрос от ответа.
         */
        const val QUERY_PREFIX = "task: search result | query: "

        /** Та же разметка, что у продуктов в tools/embeddb: свои продукты сравниваются с ними на равных. */
        const val DOCUMENT_PREFIX = "title: none | text: "

        /** Человек вводит еду подряд по нескольку строк — на это время движок остаётся в памяти. */
        const val IDLE_MILLIS = 120_000L

        val OPTIONS = EmbeddingOptions(normalize = true)
    }
}
