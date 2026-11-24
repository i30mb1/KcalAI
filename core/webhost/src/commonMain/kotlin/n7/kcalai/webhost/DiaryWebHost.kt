package n7.kcalai.webhost

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import n7.kcalai.database.WebClientDao
import n7.kcalai.model.PageGrace
import n7.kcalai.nearby.LanRoutes
import n7.kcalai.webapi.WEB_SOCKET_PATH
import n7.kcalai.webapi.WebPeer
import n7.kcalai.webapi.encode
import n7.kcalai.webapi.parseToPhone

/** Собранная страница дневника: файл по пути внутри неё; `null` — такого нет. */
fun interface WebBundle {
    suspend fun read(path: String): ByteArray?
}

/**
 * Страница дневника для браузера в домашней сети — на том же сервере, что приёмник соседей.
 *
 * Файлы страницы отдаются с самого телефона: своего хостинга у неё нет, и дневник
 * не уходит дальше домашней сети. Разговор — по WebSocket, каждый браузер — своя
 * [WebSession]: пока человек не пустил его тапом, он не видит ничего.
 *
 * @param owner имя хозяина телефона; `null` — не назвался
 */
class DiaryWebHost(
    private val clients: WebClientDao,
    private val diary: WebDiary,
    private val owner: Flow<String?>,
    private val grace: Flow<PageGrace>,
    private val peers: Flow<List<WebPeer>>,
    private val presence: WebPresence,
    private val bundle: WebBundle,
) : LanRoutes {

    override fun Application.install() {
        install(WebSockets)
        routing {
            webSocket(WEB_SOCKET_PATH) {
                WebSession(
                    clients = clients,
                    diary = diary,
                    // Имя не обязательно: свой дневник открывают и без него, подпись тогда пустая.
                    // Потоком: сменили имя со страницы — подпись меняется тут же.
                    owner = owner.map { it.orEmpty() },
                    grace = grace,
                    peers = peers,
                    label = browserLabel(call.request.headers[HttpHeaders.UserAgent]),
                    presence = presence,
                ).run(
                    incoming = incoming.consumeAsFlow().mapNotNull { frame -> (frame as? Frame.Text)?.readText()?.let(::parseToPhone) },
                    send = { message -> send(Frame.Text(encode(message))) },
                )
            }
            get("/") { call.serve(INDEX) }
            get("/{path...}") { call.serve(call.parameters.getAll("path").orEmpty().joinToString("/")) }
        }
    }

    private suspend fun RoutingCall.serve(path: String) {
        val bytes = path.takeIf(::isInside)?.let { bundle.read(it) }
        if (bytes == null) {
            respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
            return
        }
        // Навсегда — только wasm: у него в имени хеш содержимого, и новая сборка — новое имя.
        // Страницу, web.js и шрифты браузер перепроверяет: имена у них постоянные, и после
        // обновления приложения он иначе остался бы на старой странице.
        response.header(HttpHeaders.CacheControl, if (path.endsWith(".wasm")) "max-age=31536000, immutable" else "no-cache")
        respondBytes(bytes, contentType(path))
    }

    private companion object {
        const val INDEX = "index.html"
    }
}

/** Путь внутри страницы: без «..», обратных слешей и абсолютных путей. */
private fun isInside(path: String): Boolean =
    path.isNotEmpty() && !path.startsWith("/") && '\\' !in path && path.split('/').none { it == ".." || it.isEmpty() }

/** Тип по расширению. `application/wasm` обязателен: без него браузер не соберёт модуль на лету. */
private fun contentType(path: String): ContentType = when (path.substringAfterLast('.', "")) {
    "html" -> ContentType.Text.Html
    "js" -> ContentType.Application.JavaScript
    "wasm" -> ContentType("application", "wasm")
    "json" -> ContentType.Application.Json
    "css" -> ContentType.Text.CSS
    "ttf" -> ContentType("font", "ttf")
    "png" -> ContentType.Image.PNG
    "svg" -> ContentType.Image.SVG
    else -> ContentType.Application.OctetStream
}
