package n7.kcalai.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File

/** Все маршруты сервера. Тесты поднимают то же самое через `testApplication`. */
fun Application.kcalServer(db: Db, dataDir: File, isLocal: (ApplicationCall) -> Boolean = ::fromThisComputer) {
    routing {
        route("/v1") {
            productRoutes(db)
            contributionRoutes(db)
            seedRoutes(File(dataDir, "seed"))
            scanRoutes(File(dataDir, "scans"))
        }
        adminRoutes(db, SeedLookup(File(dataDir, "seed/seed.db")), File(dataDir, "scans"), isLocal)
    }
}
