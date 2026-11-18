package n7.kcalai.assistant

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.mlkit.genai.agent.Agent
import com.google.mlkit.genai.agent.AgentEvent
import com.google.mlkit.genai.agent.AgentRunner
import com.google.mlkit.genai.agent.AgentTool
import com.google.mlkit.genai.agent.AppFunctionAgentTools
import com.google.mlkit.genai.agent.textTokens
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.content
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import n7.kcalai.model.ModelDelivery
import n7.kcalai.resolver.ResolvedItem

/**
 * Gemini Nano как агент: сам ищет в базе, сам выбирает, — но только предлагает.
 *
 * Инструменты — AppFunctions приложения (см. `KcalTools` в `:app`), открытые
 * только своему агенту. Модель зовёт их по имени, ML Kit исполняет и отдаёт
 * результат обратно в цикл; экран видит каждый вызов событием потока и вызовы
 * `propose_food` превращает в чипсы. Записывать в дневник модель не может вовсе:
 * такого инструмента у неё нет.
 *
 * Требует Android 16: AppFunctions — платформенный API. На телефонах, где Gemini
 * Nano есть, это и так обычно 16-й.
 *
 * @param scope где докачивается модель, если AICore её ещё не скачал: дольше экрана
 */
class NanoAssistant(
    private val context: Context,
    private val tools: DiaryTools,
    private val scope: CoroutineScope,
) : FoodAssistant {

    private val model by lazy { Generation.getClient() }
    private val downloading = AtomicBoolean(false)
    private val deliveryState = MutableStateFlow<ModelDelivery?>(null)

    /**
     * Докачка модели — для реплик персонажей. `null` — говорить не о чем: модели
     * на этом телефоне нет или про неё ещё не спрашивали.
     */
    val delivery: StateFlow<ModelDelivery?> = deliveryState.asStateFlow()

    override suspend fun ready(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> {
                deliveryState.value = ModelDelivery.READY
                true
            }
            FeatureStatus.DOWNLOADABLE -> {
                // Сейчас — нет, но к следующему открытию экрана будет.
                download()
                false
            }
            FeatureStatus.DOWNLOADING -> {
                deliveryState.value = ModelDelivery.DOWNLOADING
                false
            }
            else -> false
        }
    }

    private fun download() {
        if (!downloading.compareAndSet(false, true)) return
        deliveryState.value = ModelDelivery.DOWNLOADING
        scope.launch {
            try {
                model.download().collect { status ->
                    Log.i(TAG, "Gemini Nano: $status")
                    when (status) {
                        is DownloadStatus.DownloadCompleted -> deliveryState.value = ModelDelivery.READY
                        is DownloadStatus.DownloadFailed -> deliveryState.value = ModelDelivery.FAILED
                        else -> Unit
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Gemini Nano не скачалась", error)
                deliveryState.value = ModelDelivery.FAILED
            } finally {
                downloading.set(false)
            }
        }
    }

    override suspend fun propose(text: String): List<ResolvedItem> {
        val agent = Agent.builder()
            .setName("KcalParser")
            .setInstructions(AssistantPrompts.PARSE_WITH_TOOLS)
            .addTools(tools(SEARCH, DiaryTools.PROPOSE))
            .setMaxTurns(PARSE_TURNS)
            .build()

        val picks = mutableListOf<ResolvedItem>()
        AgentRunner(context, agent, PARSE_TEMPERATURE).use { runner ->
            runner.runSingleTurnAsync(content { text(text) }).collect { event ->
                if (event is AgentEvent.FunctionCall) {
                    tools.proposal(event.name, event.args)?.let { picks += it }
                }
            }
        }
        // Одно блюдо модель иногда предлагает дважды — на втором круге цикла.
        return picks.distinctBy { it.candidate?.ref }
    }

    override fun answer(question: String): Flow<String> = flow {
        val agent = Agent.builder()
            .setName("KcalDiary")
            .setInstructions(AssistantPrompts.ANSWER_WITH_TOOLS)
            .addTools(tools(SEARCH, DIARY_DAYS, DAY_ENTRIES))
            .setMaxTurns(ANSWER_TURNS)
            .build()

        AgentRunner(context, agent, ANSWER_TEMPERATURE).use { runner ->
            emitAll(
                runner.runSingleTurnAsync(content { text(question) })
                    .textTokens()
                    .scan("") { sofar, token -> sofar + token }
                    .drop(1)
            )
        }
    }

    /**
     * Свои AppFunctions по коротким именам.
     *
     * Идентификатор функции ML Kit и платформа собирают по-разному — с пакетом
     * и классом службы или без, — поэтому сверяется хвост имени, как в [DiaryTools.proposal].
     */
    private suspend fun tools(vararg names: String): List<AgentTool> =
        AppFunctionAgentTools.discover(context).filter { tool ->
            tool.name.substringAfterLast('#').substringAfterLast('.') in names
        }

    private companion object {
        const val TAG = "NanoAssistant"

        const val SEARCH = "search_food"
        const val DIARY_DAYS = "diary_days"
        const val DAY_ENTRIES = "day_entries"

        /** Разбор фразы — не творчество: при одном и том же тексте ждём одно и то же. */
        const val PARSE_TEMPERATURE = 0.1f
        const val ANSWER_TEMPERATURE = 0.3f

        /** Поиск и предложение на каждое блюдо фразы — до четырёх блюд. */
        const val PARSE_TURNS = 9
        const val ANSWER_TURNS = 6
    }
}
