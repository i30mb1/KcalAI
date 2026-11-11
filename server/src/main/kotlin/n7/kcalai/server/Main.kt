package n7.kcalai.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.util.Properties

/**
 * Запуск: конфигурация «Сервер» в Android Studio или `./gradlew :server:run` из корня.
 * `KCAL_PORT` и `KCAL_DATA` переопределяют порт панели и каталог данных.
 *
 * Внешний адрес — в `data/server.properties` (вне git): `public.host=<IP или домен>`.
 * С ним сервер сам получает сертификат Let's Encrypt и отвечает телефонам по https
 * (порты `public.https`, по умолчанию 443, и `public.http` для проверки — 80), а панель
 * слушает только `localhost`. Без него — http на всех интерфейсах, для домашней сети.
 */
fun main() {
    val port = System.getenv("KCAL_PORT")?.toIntOrNull() ?: 8080
    val dataDir = File(System.getenv("KCAL_DATA") ?: "data").apply { mkdirs() }
    val db = Db(File(dataDir, "kcal-server.db").path)
    val config = Properties().apply {
        File(dataDir, "server.properties").takeIf { it.isFile }?.reader()?.use(::load)
    }

    println("kcal-server: данные в ${dataDir.absolutePath}")
    println("Панель: http://localhost:$port/admin")
    val host = config.getProperty("public.host")?.trim()?.takeIf { it.isNotEmpty() }
    if (host == null) {
        embeddedServer(Netty, port = port, host = "0.0.0.0") { kcalServer(db, dataDir) }.start(wait = true)
        return
    }
    PublicServer(
        db = db,
        dataDir = dataDir,
        host = host,
        panelPort = port,
        httpPort = config.getProperty("public.http")?.toIntOrNull() ?: 80,
        httpsPort = config.getProperty("public.https")?.toIntOrNull() ?: 443,
        // Проверить настройку, не тратя лимиты боевого: acme.directory=acme://letsencrypt.org/staging
        acmeDirectory = config.getProperty("acme.directory") ?: "acme://letsencrypt.org",
    ).run()
}
