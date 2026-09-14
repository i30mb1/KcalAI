package n7.kcalai.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ContributionRoutesTest {

    private val db = Db.inMemory()
    private val dataDir = File(System.getProperty("java.io.tmpdir"), "kcal-test-${System.nanoTime()}")

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { kcalServer(db, dataDir) }
        block()
    }

    private val milk =
        """{"gtin":"4600699500001","name":"Молоко 3,2%","kcal100":59,"prot100":290,"fat100":320,"carb100":470,"servingG":250,"createdAt":1757721600000}"""
    private val broken =
        """{"gtin":"4600699500002","name":"Опечатка","kcal100":59,"prot100":290,"fat100":320,"carb100":470,"createdAt":1}"""
    private val absurd =
        """{"gtin":"96385074","name":"Жир","kcal100":950,"prot100":0,"fat100":10000,"carb100":0,"createdAt":1}"""

    private suspend fun ApplicationTestBuilder.send(body: String) =
        client.post("/v1/contributions") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }

    @Test
    fun `принятые попадают в ответ и в базу`() = app {
        val response = send("[$milk]")

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("""{"accepted":["4600699500001"]}""", response.bodyAsText())
        assertEquals(1, db.contributionsFor("4600699500001").size)
    }

    @Test
    fun `битый GTIN и абсурдный КБЖУ не принимаются, остальное — принимается`() = app {
        val response = send("[$milk,$broken,$absurd]")

        assertEquals("""{"accepted":["4600699500001"]}""", response.bodyAsText())
        assertEquals(0, db.contributionsFor("4600699500002").size)
    }

    @Test
    fun `повтор того же GTIN — ещё один голос`() = app {
        send("[$milk]")
        send("[$milk]")

        assertEquals(2, db.contributionsFor("4600699500001").size)
    }

    @Test
    fun `не JSON — 400`() = app {
        assertEquals(HttpStatusCode.BadRequest, send("это не json").status)
        assertEquals(HttpStatusCode.BadRequest, send("""{"gtin":"1"}""").status)
    }
}
