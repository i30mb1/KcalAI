package n7.kcalai.feature.scanner

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Карточка нового продукта: что считается заполненным.
 *
 * Подпись под названием говорит «N из 5 полей» — название и четыре числа
 * с этикетки. Штрихкод необязателен и стоит отдельной строкой: засчитай его,
 * и после промаха скана карточка открывается с «1 из 5», а заполненная
 * целиком показывает «6 из 5».
 */
class ScanStateTest {

    private fun filled(gtin: String? = GTIN) = ScanState(gtin).apply {
        name.type("Творог 5%")
        kcal.type("121")
        prot.type("16")
        fat.type("5")
        carb.type("3")
    }

    @Test
    fun `заполненная карточка — пять из пяти, сколько бы ни было в коде`() {
        assertEquals(5, filled(gtin = GTIN).filledFields)
        assertEquals(5, filled(gtin = null).filledFields)
    }

    @Test
    fun `код с промаха скана полем карточки не считается`() {
        assertEquals(0, ScanState(GTIN).filledFields)
    }

    /**
     * Закрыть карточку — одно касание «назад», а заполнить её — минута с пачкой
     * в руке. Пока терять нечего, карточка закрывается сразу; есть что терять —
     * сначала спрашивает.
     */
    @Test
    fun `терять нечего, пока в карточке только код с промаха скана`() {
        assertFalse(ScanState(GTIN).hasDraft)
        assertFalse(ScanState(null).hasDraft)
    }

    @Test
    fun `есть что терять, как только поле заполнила камера или человек`() {
        assertTrue(ScanState(GTIN).apply { name.type("Творог") }.hasDraft)
        assertTrue(ScanState(null).apply { kcal.offer("121") }.hasDraft)
        // Код, набранный руками на карточке без промаха скана, — тоже работа.
        assertTrue(ScanState(null).apply { barcode.type(GTIN) }.hasDraft)
    }

    private companion object {
        const val GTIN = "4607001771234"
    }
}
