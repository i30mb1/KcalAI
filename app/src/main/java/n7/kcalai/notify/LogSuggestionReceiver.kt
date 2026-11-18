package n7.kcalai.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import n7.kcalai.KcalApp

/**
 * «Записать: гречка» прямо из шторки, не открывая приложение.
 *
 * Что записать и как ответить, решает общий с iOS код — [n7.kcalai.mascot.MascotRoutines.logFromShade].
 * Здесь только доставка нажатия.
 */
class LogSuggestionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras?.let { bundle -> bundle.keySet().associateWith { bundle.getString(it).orEmpty() } } ?: return
        val routines = (context.applicationContext as KcalApp).container.mascotRoutines
        val pending = goAsync()
        scope.launch {
            try {
                routines.logFromShade(extras)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** Своя область: приёмник живёт миг, а запись в базу и ответ — дольше. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
