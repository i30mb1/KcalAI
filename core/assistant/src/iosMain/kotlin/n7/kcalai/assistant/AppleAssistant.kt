package n7.kcalai.assistant

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import n7.kcalai.model.Log
import n7.kcalai.resolver.ResolvedItem
import kotlin.coroutines.resume

/**
 * Нейросеть Apple на телефоне — глазами общего кода.
 *
 * Foundation Models живёт только в Swift: фреймворк построен на макросах
 * и типизированном `async`, а в бандле у него нет ни одного заголовка
 * Objective-C — значит, cinterop его не видит вовсе (проверено: в SDK 27.2
 * у FoundationModels.framework каталога Headers нет, только swiftmodule).
 *
 * Поэтому граница проведена с другой стороны: этот интерфейс Kotlin экспортирует
 * как протокол Objective-C, а реализует его оболочка на Swift. Через границу
 * ходят только строки и колбэки — ни `@Generable`, ни дженерики её не пересекают.
 *
 * Зовётся с главного потока: Kotlin/Native не пускает чужие объекты между
 * потоками без объяснений, а оболочка и так живёт в UI.
 */
interface AppleModelBridge {

    /** Есть ли на этом устройстве Apple Intelligence и скачана ли модель. */
    fun isReady(onResult: (Boolean) -> Unit)

    /**
     * Фраза -> JSON вида `[{"key":"generic:7","grams":200}]`.
     *
     * Ключи — только те, что отдал поиск: что искать, модель спрашивает
     * у [DiaryTools.searchFood] через ту же оболочку. Пустой массив — не поняла.
     */
    fun propose(text: String, onResult: (String?) -> Unit)

    /**
     * Ответ на вопрос о дневнике, по мере того как модель его пишет.
     *
     * [onChunk] зовётся на каждый кусок, [onDone] — в конце, в том числе
     * при ошибке: иначе лента ждала бы ответа вечно.
     */
    fun answer(question: String, onChunk: (String) -> Unit, onDone: () -> Unit)
}

/**
 * Оболочка на Swift -> [FoodAssistant].
 *
 * Весь перевод между мирами собран здесь: колбэки становятся корутинами
 * и потоком, JSON — предложениями. Экран об этом не знает и работает так же,
 * как с Gemini Nano на Android.
 */
class AppleAssistant(
    private val bridge: AppleModelBridge,
    private val tools: DiaryTools,
) : FoodAssistant {

    override suspend fun ready(): Boolean = suspendCancellableCoroutine { continuation ->
        bridge.isReady { ready -> continuation.resume(ready) }
    }

    override suspend fun propose(text: String): List<ResolvedItem> {
        val json = suspendCancellableCoroutine<String?> { continuation ->
            bridge.propose(text) { answer -> continuation.resume(answer) }
        } ?: return emptyList()

        // Предложение собирает тот же DiaryTools, что и на Android: ключ, которого
        // поиск не отдавал, — выдуманный, и продукта за ним нет.
        return parseProposals(json).mapNotNull { (key, grams) ->
            tools.proposal(PROPOSE_CALL, mapOf("key" to key, "grams" to grams))
        }
    }

    override fun answer(question: String): Flow<String> = callbackFlow {
        // Каждое значение — весь ответ на этот момент, а не очередной кусок:
        // экран просто показывает последнее.
        val whole = StringBuilder()
        bridge.answer(
            question = question,
            onChunk = { chunk ->
                whole.append(chunk)
                trySend(whole.toString())
            },
            onDone = { close() },
        )
        awaitClose { }
    }

    private companion object {
        const val PROPOSE_CALL = "propose_food"
    }
}

/**
 * JSON предложений -> пары «ключ, граммы».
 *
 * Руками, а не сериализацией: ответ модели — не наш формат, а её попытка его
 * повторить, и половина разбора здесь это терпимость к тому, как она ошибается.
 * Битый ответ — пустой список, а не исключение: модель не поняла — это штатно.
 */
internal fun parseProposals(json: String): List<Pair<String, Int>> = runCatching {
    OBJECT.findAll(json).mapNotNull { match ->
        val body = match.value
        val key = KEY.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
        val grams = GRAMS.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        key to grams
    }.toList()
}.getOrElse {
    Log.w("AppleAssistant", "ответ модели не разобран: $it")
    emptyList()
}

private val OBJECT = Regex("""\{[^{}]*}""")
private val KEY = Regex(""""key"\s*:\s*"([^"]+)"""")
private val GRAMS = Regex(""""grams"\s*:\s*(\d+)""")
