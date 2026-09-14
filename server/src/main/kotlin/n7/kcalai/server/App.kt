package n7.kcalai.server

import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File

/** Р’СЃРµ РјР°СЂС€СЂСѓС‚С‹ СЃРµСЂРІРµСЂР°. РўРµСЃС‚С‹ РїРѕРґРЅРёРјР°СЋС‚ С‚Рѕ Р¶Рµ СЃР°РјРѕРµ С‡РµСЂРµР· `testApplication`. */
fun Application.kcalServer(db: Db, dataDir: File) {
    routing {
        route("/v1") {
            productRoutes(db)
            contributionRoutes(db)
            seedRoutes(File(dataDir, "seed"))
            scanRoutes(File(dataDir, "scans"))
        }
    }
}
