package n7.kcalai.graph

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import n7.kcalai.database.KcalDatabase
import n7.kcalai.feature.diary.OutboxCount
import n7.kcalai.feature.diary.OutboxMemory
import n7.kcalai.mascot.MascotShade
import n7.kcalai.mascot.ShadeReply
import n7.kcalai.mascot.Written
import n7.kcalai.nearby.NearbyDiscovery

/**
 * Граф один на обе платформы. Здесь — решения, которые в нём живут, а не в
 * приложениях: без адреса сервера отправлять некуда, и Яйцо не предлагает поделиться.
 *
 * Платформа в тесте ничего не открывает: граф ленив, и до базы эти решения не доходят.
 */
class AppGraphTest {

    private class Graph(override val serverBaseUrl: String) : AppGraph() {
        override val userAgent = "KcalAI/test"
        override fun openDatabase(): KcalDatabase = error("база в этом тесте не открывается")
        override fun seedPath(): String = error("справочник в этом тесте не открывается")
        override fun discovery(): NearbyDiscovery = error("соседи в этом тесте не ищутся")
        override fun shade(): MascotShade = object : MascotShade {
            override suspend fun canPost() = false
            override suspend fun post(written: Written) = Unit
            override suspend fun postReply(reply: ShadeReply) = Unit
        }
        override fun outboxMemory(): OutboxMemory = object : OutboxMemory {
            override suspend fun declined(): OutboxCount? = null
            override suspend fun remember(declined: OutboxCount?) = Unit
        }
    }

    @Test
    fun `без адреса сервера отправлять некуда`() {
        val graph = Graph(serverBaseUrl = " ")

        assertFalse(graph.serverConfigured)
        assertNull(graph.outbox, "Яйцо не предлагает поделиться — некуда")
    }

    @Test
    fun `с адресом сервер настроен`() {
        assertTrue(Graph(serverBaseUrl = "https://kcal.example").serverConfigured)
    }
}
