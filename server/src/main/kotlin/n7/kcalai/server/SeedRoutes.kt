package n7.kcalai.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Справочник, собранный `build_seed.py --install`. Хеш и версия лежат рядом
 * с файлом: считать хеш на каждый запрос незачем, файл меняется раз в день.
 */
fun Route.seedRoutes(seedDir: File) {
    val file = File(seedDir, "seed.db")
    val digest = File(seedDir, "seed.db.sha256")
    val version = File(seedDir, "version.txt")

    get("/seed/manifest.json") {
        if (!file.isFile || !digest.isFile) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val body = buildJsonObject {
            put("version", version.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0)
            put("sha256", digest.readText().trim())
            put("size", file.length())
            put("url", "/v1/seed/seed.db")
        }
        call.respondText(body.toString(), ContentType.Application.Json)
    }

    get("/seed/seed.db") {
        if (!file.isFile) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respond(LocalFileContent(file, ContentType.Application.OctetStream))
    }
}
