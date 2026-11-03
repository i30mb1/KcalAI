package n7.kcalai.semantic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Тяжёлый ресурс, который живёт, пока им пользуются, и закрывается после [idleMillis] простоя.
 *
 * Движок поиска по смыслу держит в памяти ~260 МБ — больше, чем всё остальное приложение.
 * Нужен он изредка: когда поиск по словам не нашёл ничего. Держать его до конца процесса
 * значит отнимать эту память у системы часами ради пары запросов, а заново он поднимается
 * за полсекунды.
 *
 * Не потокобезопасен: [use] и закрытие по таймеру обязаны идти на одном однопоточном
 * диспетчере — том, на котором живёт [scope].
 *
 * @param open `null` — открыть нечего (модели нет), и таймер не заводится
 */
internal class IdleHolder<T : AutoCloseable>(
    private val scope: CoroutineScope,
    private val idleMillis: Long,
    private val open: () -> T?,
) {

    private var value: T? = null
    private var closer: Job? = null

    fun <R> use(block: (T) -> R): R? {
        closer?.cancel()
        val resource = value ?: open()?.also { value = it } ?: return null
        try {
            return block(resource)
        } finally {
            closer = scope.launch {
                delay(idleMillis)
                value?.close()
                value = null
            }
        }
    }
}
