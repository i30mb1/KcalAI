package n7.kcalai.assistant

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * как протокол Objective-C, а реализует его оболочка на Swift. Оболочка тонкая:
 * ей дают инструкцию и запрос — она возвращает ответ модели. Что спросить и как
 * понять ответ, решает общий код, тот же, что для Gemini Nano, — см. [AssistantPrompts].
 *
 * Зовётся с главного потока: Kotlin/Native не пускает чужие объекты между
 * потоками без объяснений, а оболочка и так живёт в UI.
 */
interface AppleModelBridge {

    /** Есть ли на этом устройстве Apple Intelligence и скачана ли модель. */
    fun isReady(onResult: (Boolean) -> Unit)

    /** Один ответ целиком; `null` — модель не ответила или отказалась. */
    fun respond(instructions: String, prompt: String, onResult: (String?) -> Unit)

    /**
     * Ответ в разговоре, по мере того как модель его пишет. Разговор живёт между
     * вопросами: «а белка сколько?» следом за прошлым вопросом должно быть понятно.
     *
     * [onChunk] зовётся на каждый новый кусок, [onDone] — в конце, в том числе
     * при ошибке: иначе лента ждала бы ответа вечно.
     */
    fun chat(instructions: String, prompt: String, onChunk: (String) -> Unit, onDone: () -> Unit)
}

/**
 * Оболочка на Swift -> [FoodAssistant].
 *
 * Своих инструментов у модели Apple здесь нет, поэтому поиск и итоги дневника
 * подаются ей заранее — тем же [DiaryTools], что зовёт Gemini Nano.
 */
class AppleAssistant(
    private val bridge: AppleModelBridge,
    private val tools: DiaryTools,
) : FoodAssistant {

    override suspend fun ready(): Boolean = suspendCancellableCoroutine { continuation ->
        bridge.isReady { ready -> continuation.resume(ready) }
    }

    override suspend fun propose(text: String): List<ResolvedItem> {
        val found = tools.searchFood(text)
        val json = suspendCancellableCoroutine<String?> { continuation ->
            bridge.respond(AssistantPrompts.PARSE_FROM_LIST, AssistantPrompts.parsePrompt(text, found)) { answer ->
                continuation.resume(answer)
            }
        } ?: return emptyList()
        return proposalsFrom(json, text, tools)
    }

    override fun answer(question: String): Flow<String> = callbackFlow {
        val diary = tools.diaryDays(DIARY_DAYS)
        // Каждое значение — весь ответ на этот момент, а не очередной кусок:
        // экран просто показывает последнее.
        val whole = StringBuilder()
        bridge.chat(
            instructions = AssistantPrompts.ANSWER_FROM_DIARY,
            prompt = AssistantPrompts.answerPrompt(question, diary),
            onChunk = { chunk ->
                whole.append(chunk)
                trySend(whole.toString())
            },
            onDone = { close() },
        )
        awaitClose { }
    }

    private companion object {
        /** Неделя — столько же, сколько видит Gemini Nano, спросив `diary_days` по умолчанию. */
        const val DIARY_DAYS = 7
    }
}
