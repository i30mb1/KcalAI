package n7.kcalai.feature.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Поведение окна съёмки, которое раньше жило дважды — в окне Android и в окне iOS.
 * Теперь окно одно, общее, и что оно показывает и куда отдаёт код, проверяется здесь.
 */
class ScanFlowTest {

    @Test
    fun `подсказка внизу — поиск, ожидание доступа, наведение`() {
        assertEquals("Ищу товар…", scanHint(searching = true, answered = true))
        assertEquals("Запрашиваем доступ к камере…", scanHint(searching = false, answered = false))
        assertEquals("Наведите на штрих-код", scanHint(searching = false, answered = true))
    }

    @Test
    fun `внизу карточка — когда она есть`() {
        val card = ScanState(GTIN)

        assertEquals(Bottom.Card(card), scanBottom(card, typingCode = true, answered = true, granted = false, searching = false))
    }

    /** Отказ в камере — не тупик: внизу сразу ввод цифр. Пока не ответили — рано. */
    @Test
    fun `без камеры — ввод цифр, но только после ответа`() {
        assertEquals(Bottom.Code(false), scanBottom(null, typingCode = false, answered = true, granted = false, searching = false))
        assertEquals(Bottom.Hint(false), scanBottom(null, typingCode = false, answered = false, granted = false, searching = false))
    }

    @Test
    fun `с камерой — подсказка, а по кнопке — ввод цифр`() {
        assertEquals(Bottom.Hint(true), scanBottom(null, typingCode = false, answered = true, granted = true, searching = true))
        assertEquals(Bottom.Code(true), scanBottom(null, typingCode = true, answered = true, granted = true, searching = true))
    }

    /** Камера читает код десятки раз подряд, а набранный руками в ту же секунду — всё ещё один товар. */
    @Test
    fun `код уходит наружу один раз — и с камеры, и с клавиатуры`() {
        val scanned = mutableListOf<String>()
        val router = BarcodeRouter { scanned += it }

        router.route(ScanStep.Barcode, card = null, code = GTIN)
        router.route(ScanStep.Barcode, card = null, code = GTIN)
        router.submit(OTHER)

        assertEquals(listOf(GTIN), scanned)
    }

    @Test
    fun `пока товар ищется — код с камеры никуда не идёт`() {
        val scanned = mutableListOf<String>()

        BarcodeRouter { scanned += it }.route(ScanStep.Searching(GTIN), card = null, code = OTHER)

        assertTrue(scanned.isEmpty())
    }

    @Test
    fun `в карточке код с камеры встаёт в её поле, а не уходит наружу`() {
        val scanned = mutableListOf<String>()
        val card = ScanState(null)

        BarcodeRouter { scanned += it }.route(ScanStep.NewProduct(), card, GTIN)

        assertEquals(GTIN, card.gtin)
        assertTrue(scanned.isEmpty())
    }

    private companion object {
        const val GTIN = "4607001771302"
        const val OTHER = "4600605000117"
    }
}
