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
import n7.kcalai.KcalApp
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.Log
import n7.kcalai.model.Mascot

/**
 * Сообщение персонажа по команде — только в отладочной сборке.
 *
 * Настоящий повод написать бывает пару раз в день и в своё время: ждать его,
 * чтобы посмотреть на шторку, — часы. Отсюда та же сборка фразы по фактам
 * дневника и та же шторка, но тему и автора задаёт разработчик:
 *
 * `adb shell am broadcast -n n7.kcalai/.notify.DebugWriteReceiver --es topic LONG_GAP --es mascot GRAIN`
 */
class DebugWriteReceiver : BroadcastReceiver() {

    @OptIn(ExperimentalTime::class)
    override fun onReceive(context: Context, intent: Intent) {
        val topic = Topic.entries.find { it.name == intent.getStringExtra("topic") } ?: Topic.LONG_GAP
        val mascot = Mascot.entries.find { it.name == intent.getStringExtra("mascot") } ?: Mascot.GRAIN
        val brain = (context.applicationContext as KcalApp).container.mascotBrain
        val pending = goAsync()

        scope.launch {
            try {
                val written = brain.write(topic, mascot, Clock.System.now())
                if (written == null) Log.i(TAG, "$mascot молчит на $topic: по фактам дня сказать нечего")
                written?.let(MascotNotifier(context)::post)
            } catch (error: Exception) {
                Log.e(TAG, "отладочное сообщение не вышло", error)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugWrite"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
