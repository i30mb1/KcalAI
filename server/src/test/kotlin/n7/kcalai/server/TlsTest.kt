package n7.kcalai.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HTTPS на внешний IP: проверка Let's Encrypt, открытый порт 80, хранение сертификата. */
class TlsTest {

    private val db = Db.inMemory()
    private val dataDir = File(System.getProperty("java.io.tmpdir"), "kcal-test-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `Let's Encrypt получает ответ на свою проверку`() = testApplication {
        val challenges = ConcurrentHashMap(mapOf("token-1" to "token-1.thumbprint"))
        application { kcalServer(db, dataDir, challenges = challenges) }

        val response = client.get("/.well-known/acme-challenge/token-1")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("token-1.thumbprint", response.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/.well-known/acme-challenge/other").status)
    }

    /** Порт 80 открыт в интернет ради проверки — данные по нему не ходят, только по https. */
    @Test
    fun `на открытом http-порту — только проверка, остальное уводится на https`() = testApplication {
        val challenges = ConcurrentHashMap(mapOf("token-1" to "token-1.thumbprint"))
        application { kcalServer(db, dataDir, challenges = challenges, plainPublic = PlainPublic(port = 80, host = "93.84.96.193")) }
        val client = createClient { followRedirects = false }

        val redirect = client.get("/v1/products/4600699500001")

        assertEquals(HttpStatusCode.MovedPermanently, redirect.status)
        assertEquals("https://93.84.96.193/v1/products/4600699500001", redirect.headers[HttpHeaders.Location])
        assertEquals(HttpStatusCode.OK, client.get("/.well-known/acme-challenge/token-1").status)
    }

    @Test
    fun `сертификата нет — его надо получить, хранилища для https пока нет`() {
        val store = CertStore(File(dataDir, "tls"))

        assertNull(store.keyStore())
        assertTrue(store.needsRenewal(Instant.now()))
    }

    /** Сертификат живёт шесть дней — продлевается, когда осталось меньше половины. */
    @Test
    fun `свежий сертификат держится, к концу срока — продлевается`() {
        val store = CertStore(File(dataDir, "tls"))
        val issued = Instant.parse("2026-11-11T00:00:00Z")
        writeSelfSigned(store, issued, issued.plus(Duration.ofHours(160)))

        assertNotNull(store.keyStore())
        assertFalse(store.needsRenewal(issued.plus(Duration.ofHours(40))))
        assertTrue(store.needsRenewal(issued.plus(Duration.ofHours(90))))
    }

    private fun writeSelfSigned(store: CertStore, from: Instant, until: Instant) {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=93.84.96.193")
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date.from(from), Date.from(until), name, keys.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        store.dir.mkdirs()
        JcaPEMWriter(store.keyFile.writer()).use { it.writeObject(keys) }
        JcaPEMWriter(store.certFile.writer()).use { it.writeObject(cert) }
    }
}
