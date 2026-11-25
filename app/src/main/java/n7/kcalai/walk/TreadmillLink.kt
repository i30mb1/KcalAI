package n7.kcalai.walk

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import n7.kcalai.repositories.TreadmillFeed

/**
 * Связь с беговой дорожкой — одна на процесс. Живёт не в сервисе-спутнике: система будит его, когда
 * дорожка рядом, но отвязывает через несколько секунд, а связь должна жить, пока по дорожке идут.
 * Пакеты — в общий [TreadmillFeed].
 */
class TreadmillLink(private val context: Context, private val feed: TreadmillFeed, private val scope: CoroutineScope) {

    private var gatt: TreadmillGatt? = null

    /** Подключена ли дорожка. Подключённая не показывается в эфире — система зовёт её «потерянной». */
    val connected: Boolean get() = gatt?.connected == true

    /**
     * Дорожка рядом — подключиться, если ещё нет.
     *
     * @param waiting ждать дорожку, пока жив процесс (ниже Android 12 систему некому будить): тогда
     *        обрыв связи не забывает её — Android подключится снова, когда дорожка появится
     */
    @Synchronized
    fun appeared(waiting: Boolean = false) {
        if (gatt != null) return
        val address = TreadmillCompanion(context).address ?: return
        gatt = TreadmillGatt(
            context = context,
            address = address,
            onPacket = { bytes -> scope.launch { feed.packet(bytes) } },
            onGone = { if (waiting) feed.gone() else lost() },
        ).takeIf { it.connect(autoConnect = waiting) }
    }

    /** Связь оборвалась: забыть её — появится дорожка снова, подключимся заново. */
    @Synchronized
    fun lost() {
        gatt?.close()
        gatt = null
        feed.gone()
    }
}
