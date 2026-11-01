package n7.kcalai.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import n7.kcalai.KcalApp
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.EntrySource
import n7.kcalai.model.Log

/**
 * «Записать: гречка» прямо из шторки.
 *
 * Обычная еда человека ложится в дневник его обычной порцией, а персонаж отвечает
 * в той же переписке — как друг, которому написали «ага, поел». Приложение
 * при этом не открывается: человек мог быть посреди чего угодно.
 */
class LogSuggestionReceiver : BroadcastReceiver() {

    @OptIn(ExperimentalTime::class)
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras?.let { bundle -> bundle.keySet().associateWith { bundle.getString(it).orEmpty() } }
        val decoded = extras?.let(SuggestionExtras::decode) ?: return
        val container = (context.applicationContext as KcalApp).container
        val pending = goAsync()

        scope.launch {
            try {
                val now = Clock.System.now()
                val zone = TimeZone.currentSystemDefault()
                val candidate = decoded.suggestion.candidate
                val grams = candidate.servingG ?: return@launch
                container.diaryRepository.add(
                    candidate = candidate,
                    grams = grams,
                    meal = decoded.suggestion.meal,
                    date = now.toLocalDateTime(zone).date.toEpochDays(),
                    source = EntrySource.MANUAL,
                    now = now.toEpochMilliseconds(),
                )
                container.personalRepository.invalidate()

                val brain = container.mascotBrain
                brain.markOpened(decoded.messageId, now)
                val reply = brain.answer(Topic.LOGGED, decoded.mascot, now, food = candidate.displayName)
                MascotNotifier(context).postReply(
                    mascot = decoded.mascot,
                    original = brain.message(decoded.messageId),
                    logged = "${candidate.displayName}, $grams г",
                    loggedAt = now.toEpochMilliseconds(),
                    reply = reply,
                )
            } catch (error: Exception) {
                Log.e(TAG, "запись из шторки не удалась", error)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "LogSuggestion"

        /** Своя область: приёмник живёт миг, а запись в базу и ответ — дольше. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
