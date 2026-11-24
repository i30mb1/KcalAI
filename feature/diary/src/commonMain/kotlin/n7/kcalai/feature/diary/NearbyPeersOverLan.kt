package n7.kcalai.feature.diary

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlin.time.Duration
import n7.kcalai.model.Nutriments
import n7.kcalai.nearby.NearbyDiscovery
import n7.kcalai.nearby.OfferPacket
import n7.kcalai.nearby.OfferReceiver
import n7.kcalai.nearby.OfferSender
import n7.kcalai.nearby.Peer
import n7.kcalai.nearby.graceStarted
import n7.kcalai.nearby.pageUrl

/**
 * Соседи по общей сети — для экрана.
 *
 * Одна реализация на обе платформы: своё у них только обнаружение
 * ([NearbyDiscovery]), а приёмник, отправка и правила — общие.
 *
 * Жизнь приёмника привязана к подписке на [nearby], но живёт он в области приложения
 * ([scope]): экран подписан — телефон слушает и объявляет себя; экран ушёл — сервер
 * держится ещё [grace] и гаснет, а вернулся раньше — продолжается без перезапуска.
 * Мельком заблокировал телефон — страница в браузере не заметит. Отдельно заводить
 * и гасить его никому не нужно, и забыть выключить нельзя. Дольше запаса платформы
 * сами не дают: на Android фоновая служба намеренно исключена, а iOS приостанавливает
 * приложение через десятки секунд после ухода.
 *
 * Пока человек не назвал имя, соседи молчат: соседу нужно видеть, кто ему пишет,
 * а «кто-то» в ленте — не предложение. Имя спрашивает Яйцо, и до ответа
 * ни объявления, ни поиска нет. Сервер при этом поднят: страница дневника
 * в браузере имени не требует — свой дневник открывают и без него.
 *
 * @param myName имя владельца; `null` — ещё не назвал
 * @param scope где живёт сервер — область приложения, а не экрана
 * @param grace сколько держать сервер после ухода экрана; `null` — пока система не остановит
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NearbyPeersOverLan(
    private val discovery: NearbyDiscovery,
    private val receiver: OfferReceiver,
    private val myName: Flow<String?>,
    scope: CoroutineScope,
    grace: Flow<Duration?> = flowOf(Duration.ZERO),
    private val sender: OfferSender = OfferSender(),
) : NearbyPeers {

    /** Адреса соседей: экран знает только имя и ключ, адрес — дело этого класса. */
    private var known = emptyMap<String, Peer>()

    private val page = MutableStateFlow<String?>(null)

    private val around = MutableStateFlow<List<Peer>>(emptyList())

    /** Соседи с адресами — для страницы дневника: у каждого из них своя страница. */
    val lanPeers: StateFlow<List<Peer>> = around.asStateFlow()

    override fun address(): Flow<String?> = page

    /** Сервер и соседи — одни на всех, кто смотрит: экран и запас после него. */
    private val running: SharedFlow<List<Peer>> = flow {
        // Сервер — на всё время экрана, а не на имя: смена имени (в том числе со страницы)
        // не должна обрывать страницу, с которой его и меняли.
        val port = receiver.start()
        // Страница дневника — на том же сервере: её адрес живёт, пока он поднят.
        page.value = discovery.localAddress()?.let { pageUrl(it, port) }
        try {
            emitAll(
                myName
                    // Профиль повторяет себя на каждое изменение базы — объявляемся заново только на новое имя.
                    .map { it?.takeIf(String::isNotBlank) }
                    .distinctUntilChanged()
                    .flatMapLatest { name -> announced(name, port) },
            )
        } finally {
            page.value = null
            around.value = emptyList()
            discovery.stop()
            receiver.stop()
        }
    }.onEach { peers ->
        known = peers.associateBy { it.key }
        around.value = peers
    }.shareIn(scope, graceStarted(grace), replay = 1)

    override fun nearby(): Flow<List<NearbyPerson>> =
        running.map { peers -> peers.map { NearbyPerson(id = it.key, name = it.name) } }

    /** @param name как объявиться соседям; `null` — назваться нечем: ни объявляемся, ни ищем */
    private fun announced(name: String?, port: Int): Flow<List<Peer>> =
        if (name == null) {
            flowOf(emptyList())
        } else {
            flow {
                discovery.advertise(name, port)
                emitAll(discovery.peers())
            }
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
