package n7.kcalai.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File

/**
 * Все маршруты сервера. Тесты поднимают то же самое через `testApplication`.
 *
 * @param challenges ответы на проверку Let's Encrypt, пока она идёт
 * @param plainPublic открытый в интернет http-порт; `null` — сервер только в домашней сети
 */
fun Application.kcalServer(
    db: Db,
    dataDir: File,
    isLocal: (ApplicationCall) -> Boolean = ::fromThisComputer,
    challenges: Map<String, String> = emptyMap(),
    plainPublic: PlainPublic? = null,
) {
    plainPublic?.let { redirectPlainToHttps(it) }
    routing {
        challengeRoutes(challenges)
        route("/v1") {
            productRoutes(db)
            contributionRoutes(db)
            seedRoutes(File(dataDir, "seed"))
            scanRoutes(File(dataDir, "scans"))
        }
        adminRoutes(db, SeedLookup(File(dataDir, "seed/seed.db")), File(dataDir, "scans"), isLocal)
    }
}
