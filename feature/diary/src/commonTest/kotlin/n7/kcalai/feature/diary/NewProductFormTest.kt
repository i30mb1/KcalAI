package n7.kcalai.feature.diary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import n7.kcalai.model.Mascot
import n7.kcalai.model.Nutriments

/**
 * Свой продукт из ненайденного слова: название уже вписано, цифры на 100 г
 * человек вписывает сам, штрих-код — если он есть. У маминого пирога его нет.
 */
class NewProductFormTest {

    private val pie = NewProductForm(name = "Мамин пирог", kcal = "320", prot = "6,5", fat = "14", carb = "42.3")

    @Test
    fun `название берётся из набранного — с большой буквы`() {
        assertEquals("Мамин пирог", newProductFormFor("  мамин пирог ").name)
    }

    @Test
    fun `цифры на 100 г — сотые грамма — запятая и точка одинаково`() {
        assertEquals(Nutriments(kcal100 = 320, prot100 = 650, fat100 = 1_400, carb100 = 4_230), pie.nutriments)
    }

    /** Клетчатку можно не знать: пустое поле — «неизвестно», а не ноль. */
    @Test
    fun `клетчатка необязательна`() {
        assertNull(pie.nutriments?.fiber100)
        assertEquals(210, pie.copy(fiber = "2,1").nutriments?.fiber100)
    }

    @Test
    fun `без калорий или макроса сохранить нельзя`() {
        assertNull(pie.copy(kcal = "").nutriments)
        assertFalse(pie.copy(carb = "").canSave)
        assertFalse(pie.copy(name = " ").canSave)
    }

    /** Мамин пирог — без кода, и это нормально. */
    @Test
    fun `без штрих-кода сохраняется`() {
        assertTrue(pie.canSave)
        assertNull(pie.gtin)
        assertFalse(pie.barcodeWrong)
    }

    @Test
    fun `штрих-код проверяется по контрольной цифре`() {
        val packed = pie.copy(barcode = GTIN)
        assertEquals(GTIN, packed.gtin)
        assertTrue(packed.canSave)

        val typo = pie.copy(barcode = "4607001771303")
        assertTrue(typo.barcodeWrong)
        assertFalse(typo.canSave, "код с опечаткой не уходит ни в базу, ни к людям")
    }

    /** Говорит Яйцо — хозяин общей базы: с кодом продукт найдут и другие. */
    @Test
    fun `Яйцо говорит куда уйдёт продукт`() {
        val home = newProductSpeech(pie)
        val packed = newProductSpeech(pie.copy(barcode = GTIN))

        assertEquals(Mascot.EGG, home.mascot)
        assertTrue(home.text.isNotBlank())
        assertTrue(packed.text != home.text)
    }

    private companion object {
        const val GTIN = "4607001771302"
    }
}
