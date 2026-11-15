package n7.kcalai.feature.diary

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import n7.kcalai.model.Nutriments
import n7.kcalai.nearby.NearbyDiscovery
import n7.kcalai.nearby.OfferPacket
import n7.kcalai.nearby.OfferReceiver
import n7.kcalai.nearby.OfferSender
import n7.kcalai.nearby.Peer

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
 * Пока человек не назвал имя, фича молчит: соседу нужно видеть, кто ему пишет,
 * а «кто-то» в ленте — не предложение. Имя спрашивает Яйцо, и до ответа
 * ни объявления, ни поиска нет.
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun nearby(): Flow<List<NearbyPerson>> = myName
        // Профиль приходит потоком из базы и повторяет себя на каждое её изменение,
        // а перезапуск здесь — это новый сокет на новом порту. Сосед, успевший
        // узнать прошлый порт, стучался бы в закрытую дверь.
        .distinctUntilChanged()
        .flatMapLatest { name ->
            if (name.isNullOrBlank()) {
                // Назваться нечем — ни объявляемся, ни ищем.
                flowOf(emptyList())
            } else {
                running(name)
            }
        }

    private fun running(name: String): Flow<List<NearbyPerson>> = flow {
        val port = receiver.start()
        discovery.advertise(name, port)
        try {
            emitAll(discovery.peers())
        } finally {
            discovery.stop()
            receiver.stop()
        }
    }.map { peers ->
        known = peers.associateBy { it.key }
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
