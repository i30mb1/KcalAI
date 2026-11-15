package n7.kcalai.nearby

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import n7.kcalai.model.Log

/**
 * Отправка предложения соседу.
 *
 * Свой клиент, а не общий `Http` из `:core:remote`: тот настроен на сотовую сеть
 * у полки магазина — десять секунд на соединение. Сосед в том же Wi-Fi отвечает
 * за миллисекунды, и ждать его десять секунд значит показать человеку зависший
 * экран там, где сосед просто вышел из сети.
 *
 * Правило оттуда перенимается целиком: не бросать на сетевых сбоях. Сосед уходит
 * из сети постоянно, и «не ушло» здесь — штатный исход, а не поломка.
 */
class OfferSender {

    private val client by lazy {
        HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
    }

    /** @return приняли ли предложение. `false` — не ушло: сосед молчит или отказал. */
    suspend fun send(host: String, port: Int, packet: OfferPacket): Boolean =
        sendRaw(host, port, encodeOffer(packet))

    /** Отдельно от [send] — чтобы тест мог послать то, чего отправитель не составил бы. */
    internal suspend fun sendRaw(host: String, port: Int, body: String): Boolean = try {
        val response: HttpResponse = client.post("http://$host:$port$OFFER_PATH") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        response.status.isSuccess()
    } catch (cancellation: CancellationException) {
        // Экран закрыли — это не сбой сети.
        throw cancellation
    } catch (error: Exception) {
        Log.i(TAG, "предложение не ушло: $error")
        false
    }

    private companion object {
        const val TAG = "OfferSender"

        /** Сосед в той же сети отвечает мгновенно: дольше ждать нечего. */
        const val CONNECT_TIMEOUT_MS = 2_000L
        const val REQUEST_TIMEOUT_MS = 5_000L
    }
}
