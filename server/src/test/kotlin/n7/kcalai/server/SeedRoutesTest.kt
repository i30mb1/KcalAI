package n7.kcalai.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SeedRoutesTest {

    private val db = Db.inMemory()
    private val dataDir = File(System.getProperty("java.io.tmpdir"), "kcal-test-${System.nanoTime()}").apply { mkdirs() }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { kcalServer(db, dataDir) }
        block()
    }

    @Test
    fun `без файла — 404`() = app {
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/seed/manifest.json").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/seed/seed.db").status)
    }

    @Test
    fun `манифест описывает файл, файл отдаётся целиком`() = app {
        val seed = File(dataDir, "seed").apply { mkdirs() }
        val bytes = byteArrayOf(1, 2, 3, 4)
        File(seed, "seed.db").writeBytes(bytes)
        File(seed, "seed.db.sha256").writeText("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a")
        File(seed, "version.txt").writeText("7")

        val manifest = Json.parseToJsonElement(client.get("/v1/seed/manifest.json").bodyAsText()).jsonObject
        assertEquals("7", manifest["version"]!!.jsonPrimitive.content)
        assertEquals("4", manifest["size"]!!.jsonPrimitive.content)
        assertEquals("/v1/seed/seed.db", manifest["url"]!!.jsonPrimitive.content)
        assertEquals(
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
            manifest["sha256"]!!.jsonPrimitive.content,
        )

        assertArrayEquals(bytes, client.get("/v1/seed/seed.db").bodyAsBytes())
    }
}
