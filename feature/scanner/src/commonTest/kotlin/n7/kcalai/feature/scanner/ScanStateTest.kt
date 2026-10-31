package n7.kcalai.feature.scanner

import kotlin.test.assertEquals
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

    private companion object {
        const val GTIN = "4607001771234"
    }
}
