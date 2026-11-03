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
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.content
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
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

    override suspend fun ready(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> true
            FeatureStatus.DOWNLOADABLE -> {
                // Сейчас — нет, но к следующему открытию экрана будет.
                download()
                false
            }
            else -> false
        }
    }

    private fun download() {
        if (!downloading.compareAndSet(false, true)) return
        scope.launch {
            try {
                model.download().collect { status -> Log.i(TAG, "Gemini Nano: $status") }
            } catch (error: Exception) {
                Log.w(TAG, "Gemini Nano не скачалась", error)
            } finally {
                downloading.set(false)
            }
        }
    }

    override suspend fun propose(text: String): List<ResolvedItem> {
        val agent = Agent.builder()
            .setName("KcalParser")
            .setInstructions(PARSE_INSTRUCTIONS)
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
            .setInstructions(ANSWER_INSTRUCTIONS)
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

        val PARSE_INSTRUCTIONS = """
            You turn a phrase from a Russian food diary into diary entries.
            For every dish or drink in the phrase:
            1. Call search_food with a short Russian name of it: 1-3 words, nominative case.
               If nothing is found, try once more with a simpler or more generic name.
            2. Pick the best matching line. Its first field is the foodId.
            3. Estimate how many grams were eaten from the phrase. Typical sizes:
               tablespoon 15 g, teaspoon 5 g, glass or cup 200 g, mug 250 g,
               plate of soup 300 g, plate of porridge or pasta 250 g, slice of bread 30 g,
               piece of cake 100 g. "half" and "a bit" reduce the amount.
               Without any hint, use the portion from the search line.
            4. Call propose_food with that foodId, the grams, and phrase: the words of
               the user text that describe this dish.
            Use only foodIds returned by search_food. Never invent foodIds or nutrition.
            If no line fits a dish, skip that dish. Do not write a text answer.
        """.trimIndent()

        val ANSWER_INSTRUCTIONS = """
            You answer questions about the user's food diary. Reply in Russian,
            in one to three short sentences, addressing the user informally ("ты").
            Tools: diary_days gives totals per day and what is left of today's goal;
            day_entries lists what was eaten on a day; search_food gives nutrition per 100 g.
            Use only numbers returned by the tools. Do not compute calories yourself
            beyond adding a few numbers. If the diary has no data for the question, say so.
            Give no medical advice and no diets.
        """.trimIndent()
    }
}
