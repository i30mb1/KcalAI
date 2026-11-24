package n7.kcalai.nearby

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Duration

/**
 * Когда держать общую работу: пока на неё смотрят — работает; перестали смотреть —
 * держится ещё [grace] и гаснет; вернулись раньше — продолжается без перезапуска.
 *
 * Так живёт сервер страницы дневника: экран ушёл — а страница в браузере не замечает
 * короткой блокировки телефона. Запас читается в момент ухода: поменяли его в настройках —
 * действует со следующего раза.
 *
 * @param grace сколько держать после ухода; `null` — пока система не остановит приложение
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun graceStarted(grace: Flow<Duration?>): SharingStarted = object : SharingStarted {
    override fun command(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> =
        subscriptionCount
            .map { it > 0 }
            .distinctUntilChanged()
            .transformLatest { watched ->
                if (watched) {
                    emit(SharingCommand.START)
                } else {
                    val keep = grace.first() ?: return@transformLatest
                    delay(keep)
                    emit(SharingCommand.STOP)
                }
            }
}
