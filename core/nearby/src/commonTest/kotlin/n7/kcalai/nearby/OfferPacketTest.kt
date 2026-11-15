package n7.kcalai.nearby

import n7.kcalai.model.Nutriments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Пакет, которым один телефон предлагает другому запись.
 *
 * Едет снимок КБЖУ, а не ссылка на продукт: `user:7` на чужом телефоне — другой
 * продукт, и разрешилась бы такая ссылка молча, с правдоподобным видом. Дневник
 * и сам устроен снимками, так что пакет повторяет его, а не изобретает своё.
 *
 * Чужому телефону доверия не больше, чем своему серверу: всё, что пришло,
 * проходит те же проверки, что набранное руками.
 */
class OfferPacketTest {

    private val buckwheat = Nutriments(kcal100 = 110, prot100 = 420, fat100 = 110, carb100 = 2_130, fiber100 = 280)

    @Test
    fun `предложение переживает дорогу между телефонами`() {
        val sent = OfferPacket(from = "Аня", food = "Гречка отварная", grams = 200, nutriments = buckwheat)

        val got = parseOffer(encodeOffer(sent))

        assertEquals(sent, got)
    }

    /**
     * У пачки без строки «пищевые волокна» клетчатки не ноль, а неизвестно.
     * Приедь она нулём — у получателя занизился бы дневной недобор.
     */
    @Test
    fun `неизвестная клетчатка остаётся неизвестной а не нулём`() {
        val unknown = buckwheat.copy(fiber100 = null)

        val got = parseOffer(encodeOffer(OfferPacket("Аня", "Хлеб", 50, unknown)))

        assertNull(assertNotNull(got).nutriments.fiber100)
    }

    @Test
    fun `нулевая клетчатка остаётся нулём`() {
        val zero = buckwheat.copy(fiber100 = 0)

        val got = parseOffer(encodeOffer(OfferPacket("Аня", "Масло", 10, zero)))

        assertEquals(0, assertNotNull(got).nutriments.fiber100)
    }

    /** Код товара переносим между телефонами — в отличие от ссылки на строку базы. */
    @Test
    fun `код товара едет с предложением`() {
        val sent = OfferPacket("Аня", "Творог", 180, buckwheat, gtin = "4600699500001")

        assertEquals("4600699500001", assertNotNull(parseOffer(encodeOffer(sent))).gtin)
    }

    @Test
    fun `битый пакет — null а не исключение`() {
        assertNull(parseOffer("не json вовсе"))
        assertNull(parseOffer(""))
        assertNull(parseOffer("{\"from\":\"Аня\"}"))
        assertNull(parseOffer("[]"))
    }

    /** Абсурдные числа заворачиваются тем же валидатором, что и набранные руками. */
    @Test
    fun `числа сверх физических пределов не принимаются`() {
        val absurd = Nutriments(kcal100 = 5_000, prot100 = 420, fat100 = 110, carb100 = 2_130)

        assertNull(parseOffer(encodeOffer(OfferPacket("Аня", "Ошибка", 100, absurd))))
    }

    @Test
    fun `вес вне разумного не принимается`() {
        assertNull(parseOffer(encodeOffer(OfferPacket("Аня", "Гречка", 0, buckwheat))))
        assertNull(parseOffer(encodeOffer(OfferPacket("Аня", "Гречка", -5, buckwheat))))
        assertNull(parseOffer(encodeOffer(OfferPacket("Аня", "Гречка", 100_000, buckwheat))))
    }

    /** Имя и название — с чужого телефона: пустые бессмысленны, длинные ломают ленту. */
    @Test
    fun `пустое имя или название не принимается`() {
        assertNull(parseOffer(encodeOffer(OfferPacket("", "Гречка", 200, buckwheat))))
        assertNull(parseOffer(encodeOffer(OfferPacket("Аня", "   ", 200, buckwheat))))
    }

    @Test
    fun `слишком длинные имя и название обрезаются а не отвергаются`() {
        val long = "я".repeat(500)

        val got = assertNotNull(parseOffer(encodeOffer(OfferPacket(long, long, 200, buckwheat))))

        assertEquals(OFFER_NAME_MAX, got.from.length)
        assertEquals(OFFER_NAME_MAX, got.food.length)
    }

    /** Чужая версия протокола — не наш пакет: лучше промолчать, чем понять наполовину. */
    @Test
    fun `пакет чужой версии не принимается`() {
        val future = encodeOffer(OfferPacket("Аня", "Гречка", 200, buckwheat)).replace("\"v\":$OFFER_VERSION", "\"v\":99")

        assertNull(parseOffer(future))
    }
}
