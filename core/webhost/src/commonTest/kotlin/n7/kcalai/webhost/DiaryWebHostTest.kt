package n7.kcalai.webhost

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import n7.kcalai.database.PeerOfferEntity
import n7.kcalai.nearby.OfferInbox
import n7.kcalai.nearby.OfferReceiver
import n7.kcalai.webapi.ToPage
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WEB_SOCKET_PATH
import n7.kcalai.webapi.WebSuggestion
import n7.kcalai.webapi.encode
import n7.kcalai.webapi.parseToPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Страница дневника доезжает до браузера по настоящему сокету: файлы — с телефона,
 * разговор — по WebSocket на том же порту, что приёмник соседей.
 */
class DiaryWebHostTest {

    private val clients = FakeWebClientDao()

    private val bundle = WebBundle { path ->
        when (path) {
            "index.html" -> "<html>дневник</html>".encodeToByteArray()
            "app.wasm" -> byteArrayOf(0, 97, 115, 109)
            "web.js" -> "start()".encodeToByteArray()
            else -> null
        }
    }

    private val host = DiaryWebHost(
        clients = clients,
        diary = object : WebDiary {
            override fun today() = emptyFlow<WebToday>()
            override suspend fun suggest(query: String) = emptyList<WebSuggestion>()
            override suspend fun add(query: String, index: Int, grams: Int) = Unit
            override suspend fun setGrams(entryId: Long, grams: Int) = Unit
            override suspend fun delete(entryId: Long) = Unit
        },
        owner = MutableStateFlow("Женя"),
        peers = MutableStateFlow(emptyList()),
        presence = WebPresence(),
        bundle = bundle,
    )

    private suspend fun <T> served(block: suspend (port: Int) -> T): T {
        val receiver = OfferReceiver(NoOffers, routes = host)
        val port = receiver.start()
        return try {
            block(port)
        } finally {
            receiver.stop()
        }
    }

    @Test
    fun `страница и её файлы отдаются с телефона`() = runTest(timeout = 20.seconds) {
        served { port ->
            val client = HttpClient()
            val page = client.get("http://127.0.0.1:$port/")
            assertEquals("<html>дневник</html>", page.bodyAsText())
            assertEquals("text/html", page.headers[HttpHeaders.ContentType]?.substringBefore(';'))

            val wasm = client.get("http://127.0.0.1:$port/app.wasm")
            assertEquals("application/wasm", wasm.headers[HttpHeaders.ContentType], "без этого типа браузер не соберёт wasm на лету")

            assertEquals(404, client.get("http://127.0.0.1:$port/nope.js").status.value)
        }
    }

    /**
     * Навсегда кешируется только то, у чего имя меняется с содержимым, — wasm с хешем в имени.
     * Остальное (страница, `web.js`, шрифты) браузер обязан перепроверять: иначе после
     * обновления приложения он так и остался бы на старой странице.
     */
    @Test
    fun `вечный кеш — только файлам с хешем в имени`() = runTest(timeout = 20.seconds) {
        served { port ->
            val client = HttpClient()
            assertEquals("no-cache", client.get("http://127.0.0.1:$port/").headers[HttpHeaders.CacheControl])
            assertEquals("no-cache", client.get("http://127.0.0.1:$port/web.js").headers[HttpHeaders.CacheControl])
            assertEquals(
                "max-age=31536000, immutable",
                client.get("http://127.0.0.1:$port/app.wasm").headers[HttpHeaders.CacheControl],
            )
        }
    }

    /** Отдаётся только собранная страница, а не что угодно по пути с «..». */
    @Test
    fun `за пределы страницы путь не выводит`() = runTest(timeout = 20.seconds) {
        served { port ->
            assertEquals(404, HttpClient().get("http://127.0.0.1:$port/composeResources/..%2F..%2Fsecret").status.value)
        }
    }

    @Test
    fun `новый браузер по сокету получает код, а просьба — его подпись`() = runTest(timeout = 20.seconds) {
        served { port ->
            var heard: ToPage? = null
            // CIO явно: движок по умолчанию в тестах — андроидный, а он сокетов не умеет.
            HttpClient(CIO) { install(WebSockets) }.webSocket(
                urlString = "ws://127.0.0.1:$port$WEB_SOCKET_PATH",
                request = { header(HttpHeaders.UserAgent, CHROME_ON_WINDOWS) },
            ) {
                send(Frame.Text(encode(ToPhone.Hello(token = null))))
                heard = parseToPage((incoming.receive() as Frame.Text).readText())
            }

            assertIs<ToPage.Pairing>(heard)
            assertEquals("Chrome на Windows", clients.all().single().label)
        }
    }

    private object NoOffers : OfferInbox {
        override suspend fun waiting(): Int = 0
        override suspend fun receive(offer: PeerOfferEntity) = Unit
    }

    private companion object {
        const val CHROME_ON_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}
