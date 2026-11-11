package n7.kcalai.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPair
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.shredzone.acme4j.AccountBuilder
import org.shredzone.acme4j.Identifier
import org.shredzone.acme4j.Session
import org.shredzone.acme4j.Status
import org.shredzone.acme4j.challenge.Http01Challenge
import org.shredzone.acme4j.util.KeyPairUtils
import org.slf4j.LoggerFactory

/**
 * Открытый в интернет http-порт: по нему Let's Encrypt проверяет, что адрес наш.
 * Всё остальное по нему не ходит — уводится на https.
 */
data class PlainPublic(val port: Int, val host: String)

/** Ответ на проверку http-01: токен → строка, которую ждёт Let's Encrypt. */
fun Route.challengeRoutes(challenges: Map<String, String>) {
    get("/.well-known/acme-challenge/{token}") {
        val answer = challenges[call.parameters["token"].orEmpty()]
        if (answer == null) call.respond(HttpStatusCode.NotFound) else call.respondText(answer, ContentType.Text.Plain)
    }
}

/** Всё, что пришло на открытый http-порт мимо проверки, — на тот же путь по https. */
fun Application.redirectPlainToHttps(plain: PlainPublic) {
    intercept(ApplicationCallPipeline.Plugins) {
        val call = context
        if (call.request.local.localPort != plain.port) return@intercept
        if (call.request.local.uri.startsWith("/.well-known/acme-challenge/")) return@intercept
        call.respondRedirect("https://${plain.host}${call.request.local.uri}", permanent = true)
        finish()
    }
}

/**
 * Сертификат и ключи на диске, в `data/tls/` — вне git.
 *
 * Сертификат на IP Let's Encrypt выдаёт на 160 часов; продлевается, когда осталась
 * меньше половины срока: так у неудачной попытки есть трое суток на повтор.
 */
class CertStore(val dir: File) {
    val keyFile = File(dir, "domain.key")
    val certFile = File(dir, "cert.pem")
    val accountFile = File(dir, "account.key")

    fun keyStore(): KeyStore? {
        val chain = chain() ?: return null
        if (!keyFile.isFile) return null
        val keys = keyFile.reader().use(KeyPairUtils::readKeyPair)
        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, keys.private, PASSWORD, chain.toTypedArray())
        }
    }

    fun needsRenewal(now: Instant): Boolean {
        val cert = chain()?.first() ?: return true
        val notAfter = cert.notAfter.toInstant()
        val lifetime = Duration.between(cert.notBefore.toInstant(), notAfter)
        return Duration.between(now, notAfter) < lifetime.dividedBy(2)
    }

    fun expiresAt(): Instant? = chain()?.first()?.notAfter?.toInstant()

    private fun chain(): List<X509Certificate>? =
        certFile.takeIf { it.isFile }?.inputStream()?.use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input).map { it as X509Certificate }
        }?.takeIf { it.isNotEmpty() }

    companion object {
        const val ALIAS = "kcal"

        /** Хранилище живёт только в памяти: пароль ему нужен по форме, а не для защиты. */
        val PASSWORD = "kcal".toCharArray()
    }
}

/**
 * Получение сертификата Let's Encrypt по ACME, проверка http-01.
 *
 * Создавая аккаунт, сервер принимает Subscriber Agreement Let's Encrypt — от имени того,
 * кто его запустил с `public.host` в `server.properties`.
 */
class Acme(
    private val store: CertStore,
    private val host: String,
    private val challenges: MutableMap<String, String>,
    private val directory: String = "acme://letsencrypt.org",
) {
    init {
        // acme4j подписывает запросы и строит CSR через BouncyCastle — провайдер нужен до первого вызова.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(BouncyCastleProvider())
    }

    fun issue() {
        store.dir.mkdirs()
        val accountKey = keyPair(store.accountFile)
        val domainKey = keyPair(store.keyFile)
        val account = AccountBuilder().agreeToTermsOfService().useKeyPair(accountKey).create(Session(directory))

        // Сертификат на голый IP бывает только короткий — профиль shortlived обязателен.
        val builder = account.newOrder()
        if (IP.matches(host)) builder.identifier(Identifier.ip(InetAddress.getByName(host))).profile("shortlived") else builder.domain(host)
        val order = builder.create()

        for (auth in order.authorizations) {
            if (auth.status == Status.VALID) continue
            val challenge = auth.findChallenge(Http01Challenge::class.java).orElseThrow {
                IllegalStateException("Let's Encrypt не предложил проверку http-01")
            }
            challenges[challenge.token] = challenge.authorization
            try {
                challenge.trigger()
                val status = auth.waitForCompletion(WAIT)
                check(status == Status.VALID) { "проверка адреса не прошла: $status — открыт ли порт 80 на роутере?" }
            } finally {
                challenges.remove(challenge.token)
            }
        }

        order.waitUntilReady(WAIT)
        order.execute(domainKey)
        check(order.waitForCompletion(WAIT) == Status.VALID) { "Let's Encrypt не выдал сертификат" }

        // Через временный файл: оборванная запись не должна оставить битый сертификат.
        val part = File(store.dir, "cert.pem.part")
        part.writer().use { order.certificate.writeCertificate(it) }
        Files.move(part.toPath(), store.certFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun keyPair(file: File): KeyPair =
        if (file.isFile) {
            file.reader().use(KeyPairUtils::readKeyPair)
        } else {
            KeyPairUtils.createKeyPair().also { keys -> file.writer().use { KeyPairUtils.writeKeyPair(keys, it) } }
        }

    private companion object {
        val WAIT: Duration = Duration.ofMinutes(2)
        val IP = Regex("[0-9.]+|[0-9a-fA-F:]+")
    }
}

/**
 * Сервер на внешнем адресе: https для телефонов, http на открытом порту для проверки
 * Let's Encrypt, панель — на `localhost`. Сертификат сам получается и продлевается;
 * чтобы подменить его, сервер перезапускается — это секунда раз в три дня.
 */
class PublicServer(
    private val db: Db,
    private val dataDir: File,
    private val host: String,
    private val panelPort: Int,
    private val httpPort: Int,
    private val httpsPort: Int,
    acmeDirectory: String,
) {
    private val log = LoggerFactory.getLogger("tls")
    private val challenges = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val store = CertStore(File(dataDir, "tls"))
    private val acme = Acme(store, host, challenges, acmeDirectory)

    fun run() {
        var server = start()
        while (true) {
            var wait = CHECK_EVERY
            if (store.needsRenewal(Instant.now())) {
                try {
                    log.info("получаю сертификат Let's Encrypt для $host…")
                    acme.issue()
                    server.stop(1_000, 5_000)
                    server = start()
                } catch (error: Exception) {
                    // Let's Encrypt ограничивает неудачные проверки — не чаще пяти в час.
                    log.error("сертификат не получен: ${error.message}", error)
                    wait = RETRY_AFTER
                }
            }
            Thread.sleep(wait.toMillis())
        }
    }

    private fun start(): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val keyStore = store.keyStore()
        val server = embeddedServer(
            Netty,
            configure = {
                connector {
                    this.host = "127.0.0.1"
                    port = panelPort
                }
                connector { port = httpPort }
                if (keyStore != null) {
                    sslConnector(keyStore, CertStore.ALIAS, { CertStore.PASSWORD }, { CertStore.PASSWORD }) { port = httpsPort }
                }
            },
        ) {
            kcalServer(db, dataDir, challenges = challenges, plainPublic = PlainPublic(httpPort, host))
        }.start(wait = false)
        val https = if (keyStore != null) "https://$host — до ${store.expiresAt()}" else "https ждёт сертификата"
        log.info("$https; проверка Let's Encrypt — порт $httpPort; панель — http://localhost:$panelPort/admin")
        return server
    }

    private companion object {
        val CHECK_EVERY: Duration = Duration.ofHours(6)
        val RETRY_AFTER: Duration = Duration.ofMinutes(30)
    }
}
