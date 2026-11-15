package n7.kcalai.nearby

import kotlinx.coroutines.test.runTest
import n7.kcalai.database.Choice
import n7.kcalai.database.PeerOfferEntity
import n7.kcalai.model.Nutriments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Предложение проходит весь путь: отправитель -> настоящий сокет -> приёмник -> база.
 *
 * Сокет настоящий, на порту, который выбрала система: подменив его, мы проверяли
 * бы разбор пакета, а он уже проверен отдельно. Здесь важно другое — что приёмник
 * поднимается, отвечает и кладёт предложение туда, откуда его возьмёт лента.
 */
class OfferExchangeTest {

    private val buckwheat = Nutriments(kcal100 = 110, prot100 = 420, fat100 = 110, carb100 = 2_130, fiber100 = 280)

    @Test
    fun `предложение доходит до базы получателя`() = runTest {
        val inbox = RecordingInbox()
        val receiver = OfferReceiver(inbox)
        val port = receiver.start()

        val sent = OfferSender().send(
            host = "127.0.0.1",
            port = port,
            packet = OfferPacket("Аня", "Гречка отварная", 200, buckwheat),
        )
        receiver.stop()

        assertTrue(sent, "отправитель обязан узнать, что предложение приняли")
        val stored = inbox.offers.single()
        assertEquals("Аня", stored.fromName)
        assertEquals("Гречка отварная", stored.displayName)
        assertEquals(200, stored.grams)
        assertEquals(280, stored.fiber100)
        assertEquals(null, stored.choice, "пришедшее ждёт ответа")
    }

    /** Чужого в сети не ждут: мусор не должен ни падать, ни оказываться в ленте. */
    @Test
    fun `мусор на порту в базу не попадает`() = runTest {
        val inbox = RecordingInbox()
        val receiver = OfferReceiver(inbox)
        val port = receiver.start()

        val sent = OfferSender().sendRaw(host = "127.0.0.1", port = port, body = "{\"v\":99}")
        receiver.stop()

        assertFalse(sent, "чужую версию приёмник обязан отклонить")
        assertTrue(inbox.offers.isEmpty())
    }

    /** Молчащий сосед — обычное дело: отправка возвращает «не ушло», а не падает. */
    @Test
    fun `до выключенного соседа предложение не уходит`() = runTest {
        val receiver = OfferReceiver(RecordingInbox())
        val port = receiver.start()
        receiver.stop()

        val sent = OfferSender().send(
            host = "127.0.0.1",
            port = port,
            packet = OfferPacket("Аня", "Гречка", 200, buckwheat),
        )

        assertFalse(sent)
    }

    /**
     * Предел на ждущие предложения: иначе любой в сети набьёт ленту доверху.
     * Человек всё равно подтверждает каждое, так что это про шум, а не про дневник.
     */
    @Test
    fun `сверх предела предложения не принимаются`() = runTest {
        val inbox = RecordingInbox(waiting = OFFERS_WAITING_MAX)
        val receiver = OfferReceiver(inbox)
        val port = receiver.start()

        val sent = OfferSender().send(
            host = "127.0.0.1",
            port = port,
            packet = OfferPacket("Аня", "Гречка", 200, buckwheat),
        )
        receiver.stop()

        assertFalse(sent)
        assertTrue(inbox.offers.isEmpty())
    }

    private class RecordingInbox(private val waiting: Int = 0) : OfferInbox {
        val offers = mutableListOf<PeerOfferEntity>()

        override suspend fun waiting(): Int = waiting + offers.size

        override suspend fun receive(offer: PeerOfferEntity) {
            offers += offer
        }
    }
}
