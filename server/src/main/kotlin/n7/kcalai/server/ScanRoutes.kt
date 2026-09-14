package n7.kcalai.server

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray
import java.io.File

/**
 * Сессии съёмки этикеток — кадры и расшифровка из `LabelRecorder` на телефоне.
 *
 * Ложатся как есть, по каталогу на сессию: анализировать их будут глазами
 * и скриптами, и любая база тут только мешала бы. Загрузка идемпотентна —
 * воркер на телефоне может прислать одно и то же дважды.
 */
fun Route.scanRoutes(scansDir: File) {
    post("/scans") {
        val id = call.request.headers["X-Scan-Id"]
        if (id == null || !SCAN_ID.matches(id)) {
            call.respondText("X-Scan-Id: ожидается [a-z0-9-]{1,64}", status = HttpStatusCode.BadRequest)
            return@post
        }

        val target = File(scansDir, id)
        if (target.exists()) {
            call.respond(HttpStatusCode.OK)
            return@post
        }

        // Сначала во временный каталог: оборванная загрузка не должна выглядеть
        // как готовая сессия, которую повторная попытка уже не перезапишет.
        val staging = File(scansDir, "$id.part").apply { deleteRecursively(); mkdirs() }
        var readings: String? = null
        var frames = 0
        call.receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> if (part.name == "readings") readings = part.value
                is PartData.FileItem -> {
                    val name = part.originalFileName?.let(::File)?.name
                    if (part.name == "frame" && name != null && FRAME_NAME.matches(name)) {
                        File(staging, name).writeBytes(part.provider().toByteArray())
                        frames++
                    }
                }
                else -> Unit
            }
            part.dispose()
        }

        val text = readings
        if (text == null || frames == 0) {
            staging.deleteRecursively()
            call.respondText("нужны readings и хотя бы один frame", status = HttpStatusCode.BadRequest)
            return@post
        }

        File(staging, "readings.txt").writeText(text)
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            call.respondText("не удалось сохранить сессию", status = HttpStatusCode.InternalServerError)
            return@post
        }
        call.respond(HttpStatusCode.Created)
    }
}

private val SCAN_ID = Regex("[a-z0-9-]{1,64}")
private val FRAME_NAME = Regex("frame-\\d{2,3}\\.jpg")
