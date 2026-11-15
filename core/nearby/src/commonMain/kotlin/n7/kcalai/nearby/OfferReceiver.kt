package n7.kcalai.nearby

import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import n7.kcalai.database.PeerOfferEntity
import kotlin.time.Clock

/** Куда приёмник кладёт пришедшее. Базу он не знает — её подставляет приложение. */
interface OfferInbox {

    /** Сколько предложений уже ждёт ответа: по ним считается предел. */
    suspend fun waiting(): Int

    suspend fun receive(offer: PeerOfferEntity)
}

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
 * Порт выбирает система (`port = 0`): фиксированный заняли бы другие приложения,
 * а искать соседа всё равно по имени, а не по номеру.
 */
class OfferReceiver(
    private val inbox: OfferInbox,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {

    private var server: EmbeddedServer<*, *>? = null

    /** @return порт, на котором слушаем — его объявляют соседям */
    suspend fun start(): Int {
        stop()
        val started = embeddedServer(CIO, port = 0) {
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
