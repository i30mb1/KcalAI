package n7.kcalai.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File

/**
 * Запуск: `./gradlew run`. Слушает все интерфейсы — телефон стучится по IP компьютера.
 * `KCAL_PORT` и `KCAL_DATA` переопределяют порт и каталог данных.
 */
fun main() {
    val port = System.getenv("KCAL_PORT")?.toIntOrNull() ?: 8080
    val dataDir = File(System.getenv("KCAL_DATA") ?: "data").apply { mkdirs() }
    val db = Db(File(dataDir, "kcal-server.db").path)

    println("kcal-server: http://0.0.0.0:$port, данные в ${dataDir.absolutePath}")
    embeddedServer(Netty, port = port, host = "0.0.0.0") { kcalServer(db, dataDir) }
        .start(wait = true)
}
