package n7.kcalai.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Панель разработчика: очередь вкладов, публикация в `product`, сессии съёмки.
 * Открыта только с этого компьютера — сервер смотрит в интернет.
 */
class AdminRoutesTest {

    private val db = Db.inMemory()
    private val dataDir = File(System.getProperty("java.io.tmpdir"), "kcal-test-${System.nanoTime()}").apply { mkdirs() }

    private fun app(local: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { kcalServer(db, dataDir, isLocal = { local }) }
        block()
    }

    private fun contribute(name: String, kcal: Int, prot: Int = 290, fat: Int = 320, carb: Int = 470, gtin: String = MILK, at: Long = 1) =
        db.insertContribution(Contribution(gtin, name, Nutriments(kcal, prot, fat, carb), servingG = 250, createdAt = at), receivedAt = at)

    private suspend fun ApplicationTestBuilder.json(path: String) = Json.parseToJsonElement(client.get(path).bodyAsText())

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String = "{}") =
        client.post(path) {
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }

    @Test
    fun `из интернета панель не открывается`() = app(local = false) {
        assertEquals(HttpStatusCode.Forbidden, client.get("/admin").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/admin/api/queue").status)
        assertEquals(HttpStatusCode.Forbidden, postJson("/admin/api/gtin/$MILK/reject").status)
    }

    @Test
    fun `страница панели отдаётся`() = app {
        val page = client.get("/admin")

        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("<html"))
    }

    @Test
    fun `в очереди — код, число голосов и последнее название`() = app {
        contribute("Молоко", kcal = 59, at = 1)
        contribute("Молоко 3,2%", kcal = 60, at = 2)

        val queue = json("/admin/api/queue").jsonArray

        assertEquals(1, queue.size)
        val item = queue[0].jsonObject
        assertEquals(MILK, item.str("gtin"))
        assertEquals(2, item["count"]!!.jsonPrimitive.int)
        assertEquals("Молоко 3,2%", item.str("name"))
    }

    @Test
    fun `карточка кода предлагает медиану голосов`() = app {
        contribute("Молоко", kcal = 58)
        contribute("Молоко", kcal = 60)
        contribute("Молоко 3,2%", kcal = 600, at = 3)

        val card = json("/admin/api/gtin/$MILK").jsonObject

        assertEquals(3, card["contributions"]!!.jsonArray.size)
        val suggestion = card["suggestion"]!!.jsonObject
        assertEquals(60, suggestion["kcal100"]!!.jsonPrimitive.int)
        assertEquals("Молоко 3,2%", suggestion.str("name"))
        assertEquals(250, suggestion["servingG"]!!.jsonPrimitive.int)
    }

    @Test
    fun `карточка показывает товар из справочника для сверки`() = app {
        seed(MILK, "Молоко Простоквашино 3,2%", kcal = 59)
        contribute("Молоко", kcal = 59)

        val seed = json("/admin/api/gtin/$MILK").jsonObject["seed"]!!.jsonObject

        assertEquals("Молоко Простоквашино 3,2%", seed.str("name"))
        assertEquals(59, seed["kcal100"]!!.jsonPrimitive.int)
    }

    @Test
    fun `опубликованный товар отдаётся телефону и уходит из очереди`() = app {
        contribute("Молоко", kcal = 59)

        val published = postJson(
            "/admin/api/gtin/$MILK/publish",
            """{"name":"Молоко 3,2%","brand":"Простоквашино","kcal100":59,"prot100":290,"fat100":320,"carb100":470,"servingG":250}""",
        )

        assertEquals(HttpStatusCode.OK, published.status)
        assertEquals(
            """{"gtin":"$MILK","name":"Молоко 3,2%","brand":"Простоквашино","kcal100":59,"prot100":290,"fat100":320,"carb100":470,"servingG":250}""",
            client.get("/v1/products/$MILK").bodyAsText(),
        )
        assertEquals(JsonArray(emptyList()), json("/admin/api/queue"))
        assertEquals(MILK, json("/admin/api/products").jsonArray.single().jsonObject.str("gtin"))
    }

    @Test
    fun `абсурдное КБЖУ не публикуется`() = app {
        val response = postJson(
            "/admin/api/gtin/$MILK/publish",
            """{"name":"Жир","kcal100":950,"prot100":0,"fat100":10000,"carb100":0}""",
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/products/$MILK").status)
    }

    @Test
    fun `отклонённый код уходит из очереди и не публикуется, голоса остаются в истории`() = app {
        contribute("Мусор", kcal = 1)

        assertEquals(HttpStatusCode.OK, postJson("/admin/api/gtin/$MILK/reject").status)

        assertEquals(JsonArray(emptyList()), json("/admin/api/queue"))
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/products/$MILK").status)
        assertEquals(1, json("/admin/api/gtin/$MILK").jsonObject["contributions"]!!.jsonArray.size)
    }

    @Test
    fun `новый голос после решения возвращает код в очередь`() = app {
        contribute("Мусор", kcal = 1, at = 1)
        postJson("/admin/api/gtin/$MILK/reject")
        contribute("Молоко", kcal = 59, at = 2)

        val queue = json("/admin/api/queue").jsonArray

        assertEquals(1, queue.single().jsonObject["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `снятый с публикации товар телефону больше не отдаётся`() = app {
        db.upsertProduct(Product(MILK, "Молоко", null, Nutriments(59, 290, 320, 470), null), now = 1)

        assertEquals(HttpStatusCode.OK, postJson("/admin/api/products/$MILK/delete").status)

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/products/$MILK").status)
    }

    @Test
    fun `сессии съёмки видны с расшифровкой и кадрами`() = app {
        File(dataDir, "scans/scan-1").apply { mkdirs() }.also {
            File(it, "readings.txt").writeText("ккал 59")
            File(it, "frame-00.jpg").writeBytes(byteArrayOf(1, 2, 3))
        }

        val scan = json("/admin/api/scans").jsonArray.single().jsonObject

        assertEquals("scan-1", scan.str("id"))
        assertEquals("ккал 59", scan.str("readings"))
        assertEquals("frame-00.jpg", scan["frames"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("/admin/scans/scan-1/frame-00.jpg").status)
    }

    @Test
    fun `за пределы каталога съёмок кадр не читается`() = app {
        File(dataDir, "kcal-server.db").writeText("секрет")

        assertEquals(HttpStatusCode.NotFound, client.get("/admin/scans/..%2F/kcal-server.db").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/admin/scans/scan-1/..%2F..%2Fkcal-server.db").status)
    }

    private fun seed(gtin: String, name: String, kcal: Int) {
        val dir = File(dataDir, "seed").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "seed.db").path}").use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE product_seed (barcode TEXT PRIMARY KEY, name TEXT NOT NULL, brand TEXT, kcal100 INTEGER NOT NULL, " +
                        "prot100 INTEGER NOT NULL, fat100 INTEGER NOT NULL, carb100 INTEGER NOT NULL, fiber100 INTEGER, serving_g INTEGER, " +
                        "popularity INTEGER NOT NULL DEFAULT 0)"
                )
                it.executeUpdate("INSERT INTO product_seed VALUES ('$gtin', '$name', NULL, $kcal, 290, 320, 470, NULL, NULL, 0)")
            }
        }
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private companion object {
        const val MILK = "4600699500001"
    }
}
