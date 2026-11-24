package n7.kcalai.nearby

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import n7.kcalai.database.PeerOfferEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Приёмник — единственный сервер телефона в домашней сети: на его порту живёт
 * и страница дневника для браузера.
 *
 * Порт по возможности постоянный: адрес страницы человек кладёт в закладки,
 * и меняться при каждом открытии дневника ему нельзя.
 */
class ReceiverPortTest {

    @Test
    fun `свободный порт приёмник берёт себе`() = runTest {
        val free = freePort()
        val receiver = OfferReceiver(EmptyInbox, port = free)

        val port = receiver.start()
        receiver.stop()

        assertEquals(free, port)
    }

    /** Порт заняло другое приложение — дневник всё равно открывается, просто по другому адресу. */
    @Test
    fun `занятый порт — приёмник встаёт на другой`() = runTest {
        val first = OfferReceiver(EmptyInbox, port = freePort())
        val taken = first.start()

        val second = OfferReceiver(EmptyInbox, port = taken)
        val port = second.start()
        val answer = HttpClient().get("http://127.0.0.1:$port/hi").status.value
        second.stop()
        first.stop()

        assertNotEquals(taken, port)
        assertEquals(404, answer, "второй приёмник обязан отвечать на своём порту")
    }

    @Test
    fun `на том же порту отвечает и страница`() = runTest {
        val receiver = OfferReceiver(
            EmptyInbox,
            routes = LanRoutes { routing { get("/hi") { call.respondText("привет") } } },
        )
        val port = receiver.start()

        val text = HttpClient().get("http://127.0.0.1:$port/hi").bodyAsText()
        receiver.stop()

        assertEquals("привет", text)
    }

    private suspend fun freePort(): Int {
        val probe = OfferReceiver(EmptyInbox)
        return probe.start().also { probe.stop() }
    }

    private object EmptyInbox : OfferInbox {
        override suspend fun waiting(): Int = 0
        override suspend fun receive(offer: PeerOfferEntity) = Unit
    }
}
