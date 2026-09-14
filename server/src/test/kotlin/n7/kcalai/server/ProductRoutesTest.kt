package n7.kcalai.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductRoutesTest {

    private val db = Db.inMemory()
    private val dataDir = File(System.getProperty("java.io.tmpdir"), "kcal-test-${System.nanoTime()}")

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { kcalServer(db, dataDir) }
        block()
    }

    @Test
    fun `товара нет — 404 без тела`() = app {
        val response = client.get("/v1/products/4600699500001")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `битый код — 400`() = app {
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/products/4600699500002").status)
    }

    @Test
    fun `товар отдаётся в единицах контракта`() = app {
        db.upsertProduct(
            Product("4600699500001", "Молоко 3,2%", "Домик в деревне", Nutriments(59, 290, 320, 470), 250),
            now = 1,
        )

        val response = client.get("/v1/products/4600699500001")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Молоко 3,2%", json["name"]!!.jsonPrimitive.content)
        assertEquals("320", json["fat100"]!!.jsonPrimitive.content)
        assertEquals("250", json["servingG"]!!.jsonPrimitive.content)
    }
}
