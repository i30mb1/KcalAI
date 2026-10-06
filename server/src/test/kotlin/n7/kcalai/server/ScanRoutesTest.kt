package n7.kcalai.server

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScanRoutesTest {

    private val db = Db.inMemory()
    private val dataDir = File(System.getProperty("java.io.tmpdir"), "kcal-test-${System.nanoTime()}").apply { mkdirs() }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { kcalServer(db, dataDir) }
        block()
    }

    private suspend fun ApplicationTestBuilder.upload(id: String, readings: String, frame: ByteArray) =
        client.post("/v1/scans") {
            header("X-Scan-Id", id)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("readings", readings)
                        append(
                            "frame", frame,
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"frame-00.jpg\"")
                            }
                        )
                    }
                )
            )
        }

    @Test
    fun `сессия ложится в свой каталог`() = app {
        val response = upload("scan-1757721600000", "frame-00.jpg  +0 мс  итог: ок\n", byteArrayOf(7, 7, 7))

        assertEquals(HttpStatusCode.Created, response.status)
        val dir = File(dataDir, "scans/scan-1757721600000")
        assertEquals("frame-00.jpg  +0 мс  итог: ок\n", File(dir, "readings.txt").readText())
        assertArrayEquals(byteArrayOf(7, 7, 7), File(dir, "frame-00.jpg").readBytes())
    }

    @Test
    fun `повторная загрузка не перезаписывает`() = app {
        upload("scan-1", "первая", byteArrayOf(1))
        val response = upload("scan-1", "вторая", byteArrayOf(2))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("первая", File(dataDir, "scans/scan-1/readings.txt").readText())
    }

    @Test
    fun `имя сессии с чужими символами — 400`() = app {
        assertEquals(HttpStatusCode.BadRequest, upload("../etc", "x", byteArrayOf(1)).status)
        assertFalse(File(dataDir, "etc").exists())
    }

    @Test
    fun `без заголовка — 400`() = app {
        val response = client.post("/v1/scans") {
            setBody(MultiPartFormDataContent(formData { append("readings", "x") }))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    /**
     * Сессия без кадров — оборванная загрузка, а не сессия.
     *
     * Если принять её, каталог встанет на место готового, и повторная попытка
     * телефона уже ничего не допишет: расшифровка останется без снимков навсегда.
     */
    @Test
    fun `одна расшифровка без кадров не принимается`() = app {
        val response = client.post("/v1/scans") {
            header("X-Scan-Id", "scan-no-frames")
            setBody(MultiPartFormDataContent(formData { append("readings", "итог: ок\n") }))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertFalse("каталога остаться не должно", File(dataDir, "scans/scan-no-frames").exists())
    }

    /** Имя кадра задаёт порядок разбора — чужое имя в сессию не ложится. */
    @Test
    fun `кадр с чужим именем не сохраняется`() = app {
        val response = client.post("/v1/scans") {
            header("X-Scan-Id", "scan-bad-frame")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("readings", "x")
                        append(
                            "frame", byteArrayOf(1),
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"../evil.jpg\"")
                            }
                        )
                    }
                )
            )
        }

        // Кадров не осталось, значит и сессии нет.
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertFalse(File(dataDir, "evil.jpg").exists())
        assertFalse(File(dataDir, "scans/scan-bad-frame").exists())
    }
}
