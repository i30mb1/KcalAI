package n7.kcalai.nearby

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import n7.kcalai.database.PeerOfferEntity
import n7.kcalai.model.Log
import kotlin.time.Clock

/** Куда приёмник кладёт пришедшее. Базу он не знает — её подставляет приложение. */
interface OfferInbox {

    /** Сколько предложений уже ждёт ответа: по ним считается предел. */
    suspend fun waiting(): Int

    suspend fun receive(offer: PeerOfferEntity)
}

/**
 * Ещё что-то, что телефон отдаёт в домашней сети, — на том же порту, что приёмник.
 *
 * Сервер у телефона один: так его адрес, объявленный соседям, — это и адрес
 * страницы дневника, и браузеру по ссылке соседа не нужно знать второй порт.
 */
fun interface LanRoutes {
    fun Application.install()
}

/**
 * Порт приёмника, пока он свободен. Постоянный, а не случайный: адрес страницы
 * дневника человек кладёт в закладки браузера. Из незанятого диапазона: стандартные
 * для разработки 8080 и 3000 вечно кем-то заняты.
 */
const val LAN_PORT = 47800

/** Путь, по которому сосед присылает предложение. Он же объявляется в сети. */
const val OFFER_PATH = "/offer"

/**
 * Больше стольких ждущих предложений не принимаем.
 *
 * Не защита дневника — в него без тапа всё равно ничего не попадёт, — а защита
 * ленты: иначе любой в той же сети набьёт её доверху, и человек не найдёт среди
 * чужого своё.
 */
const val OFFERS_WAITING_MAX = 10

/**
 * Приёмник предложений: слушает, пока дневник на экране.
 *
 * Сервер на самом телефоне, а не посредник в облаке: еда едет напрямую соседу
 * по домашней сети, минуя наши серверы, — так обещано в политике. На Android
 * фоновая служба намеренно исключена, а iOS сокет в фоне не держит, поэтому
 * приёмник живёт ровно столько, сколько открыт экран. Это не ограничение
 * реализации, а сценарий: оба человека рядом и оба смотрят в телефон.
 *
 * Порт — [port], а занят он — любой, какой даст система: соседи находят телефон
 * по имени, а не по номеру, и только закладка в браузере попросит обновиться.
 *
 * @param port какой порт занять, если он свободен; `0` — любой
 * @param routes что ещё отдать на этом же порту — страницу дневника
 */
class OfferReceiver(
    private val inbox: OfferInbox,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val port: Int = 0,
    private val routes: LanRoutes? = null,
) {

    private var server: EmbeddedServer<*, *>? = null

    /** @return порт, на котором слушаем — его объявляют соседям */
    suspend fun start(): Int {
        stop()
        // Порт заняло другое приложение — не повод оставаться без приёмника.
        return listen(if (port != 0 && isFree(port)) port else 0)
    }

    /**
     * Свободен ли порт — пробной привязкой. Сервер CIO о занятом порте сообщает отменой
     * и неперехваченным исключением где-то в своих корутинах, а не ошибкой старта,
     * поэтому спрашиваем заранее. Между пробой и стартом порт могут занять — тогда
     * приёмник не встанет, как не встал бы и без пробы; такого совпадения не ждём.
     */
    private suspend fun isFree(port: Int): Boolean = SelectorManager(Dispatchers.IO).use { selector ->
        try {
            aSocket(selector).tcp().bind(port = port).close()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (busy: Exception) {
            Log.i(TAG, "порт $port занят, беру любой: $busy")
            false
        }
    }

    private suspend fun listen(port: Int): Int {
        val started = embeddedServer(CIO, port = port) {
            routes?.run { install() }
            routing {
                post(OFFER_PATH) {
                    val body = call.receiveText()
                    val packet = parseOffer(body)
                    if (packet == null) {
                        // Чужой в сети или чужая версия — не повод объясняться подробно.
                        call.respondText("no", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    if (inbox.waiting() >= OFFERS_WAITING_MAX) {
                        call.respondText("full", status = HttpStatusCode.TooManyRequests)
                        return@post
                    }
                    inbox.receive(packet.toOffer(now = clock.now().toEpochMilliseconds(), day = clock.todayIn(zone).toEpochDays()))
                    call.respondText("ok")
                }
            }
        }
        started.start(wait = false)
        server = started
        return started.engine.resolvedConnectors().first().port
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
        server = null
    }

    private companion object {
        const val TAG = "OfferReceiver"
        const val STOP_TIMEOUT_MS = 500L
    }
}

/**
 * Пакет из сети -> строка для базы.
 *
 * День и время ставит получатель, а не отправитель: часы у телефонов расходятся,
 * а предложение обязано встать в сегодняшнюю переписку получателя.
 */
internal fun OfferPacket.toOffer(now: Long, day: Long) = PeerOfferEntity(
    fromName = from,
    displayName = food,
    grams = grams,
    kcal100 = nutriments.kcal100,
    prot100 = nutriments.prot100,
    fat100 = nutriments.fat100,
    carb100 = nutriments.carb100,
    fiber100 = nutriments.fiber100,
    gtin = gtin,
    dateEpochDay = day,
    createdAt = now,
)
