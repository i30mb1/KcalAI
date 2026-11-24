package n7.kcalai.feature.diary

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import n7.kcalai.model.Nutriments
import n7.kcalai.nearby.NearbyDiscovery
import n7.kcalai.nearby.OfferPacket
import n7.kcalai.nearby.OfferReceiver
import n7.kcalai.nearby.OfferSender
import n7.kcalai.nearby.Peer
import n7.kcalai.nearby.pageUrl

/**
 * Соседи по общей сети — для экрана.
 *
 * Одна реализация на обе платформы: своё у них только обнаружение
 * ([NearbyDiscovery]), а приёмник, отправка и правила — общие.
 *
 * Жизнь приёмника привязана к подписке на [nearby]: экран подписан — телефон
 * слушает и объявляет себя, экран ушёл — всё гаснет. Отдельно заводить и гасить
 * его никому не нужно, и забыть выключить нельзя. Это ровно тот срок, который
 * платформы и так позволяют: на Android фоновая служба намеренно исключена,
 * а iOS сокет в фоне не держит.
 *
 * Пока человек не назвал имя, соседи молчат: соседу нужно видеть, кто ему пишет,
 * а «кто-то» в ленте — не предложение. Имя спрашивает Яйцо, и до ответа
 * ни объявления, ни поиска нет. Сервер при этом поднят: страница дневника
 * в браузере имени не требует — свой дневник открывают и без него.
 *
 * @param myName имя владельца; `null` — ещё не назвал
 */
class NearbyPeersOverLan(
    private val discovery: NearbyDiscovery,
    private val receiver: OfferReceiver,
    private val myName: Flow<String?>,
    private val sender: OfferSender = OfferSender(),
) : NearbyPeers {

    /** Адреса соседей: экран знает только имя и ключ, адрес — дело этого класса. */
    private var known = emptyMap<String, Peer>()

    private val page = MutableStateFlow<String?>(null)

    private val around = MutableStateFlow<List<Peer>>(emptyList())

    /** Соседи с адресами — для страницы дневника: у каждого из них своя страница. */
    val lanPeers: StateFlow<List<Peer>> = around.asStateFlow()

    override fun address(): Flow<String?> = page

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun nearby(): Flow<List<NearbyPerson>> = myName
        // Профиль приходит потоком из базы и повторяет себя на каждое её изменение,
        // а перезапуск здесь — это новый сокет на новом порту. Сосед, успевший
        // узнать прошлый порт, стучался бы в закрытую дверь.
        .distinctUntilChanged()
        .flatMapLatest { name -> running(name?.takeIf { it.isNotBlank() }) }

    /** @param name как объявиться соседям; `null` — назваться нечем: ни объявляемся, ни ищем */
    private fun running(name: String?): Flow<List<NearbyPerson>> = flow {
        val port = receiver.start()
        if (name != null) discovery.advertise(name, port)
        // Страница дневника — на том же сервере: её адрес живёт, пока он поднят.
        page.value = discovery.localAddress()?.let { pageUrl(it, port) }
        try {
            if (name != null) {
                emitAll(discovery.peers())
            } else {
                emit(emptyList())
                awaitCancellation()
            }
        } finally {
            page.value = null
            around.value = emptyList()
            discovery.stop()
            receiver.stop()
        }
    }.map { peers ->
        known = peers.associateBy { it.key }
        around.value = peers
        peers.map { NearbyPerson(id = it.key, name = it.name) }
    }

    override suspend fun send(id: String, food: String, grams: Int, nutriments: Nutriments): Boolean {
        val peer = known[id] ?: return false
        val from = myName.first() ?: return false
        return sender.send(
            host = peer.host,
            port = peer.port,
            packet = OfferPacket(from = from, food = food, grams = grams, nutriments = nutriments),
        )
    }
}

/** Ключ соседа: имя у двоих в одной сети не повторяется — его назначает Bonjour. */
private val Peer.key: String get() = name
