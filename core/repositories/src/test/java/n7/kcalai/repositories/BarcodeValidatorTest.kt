package n7.kcalai.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Контрольная цифра и нормализация штрих-кода.
 *
 * Битый код не должен уходить в сеть и тем более в форму заведения продукта:
 * иначе человек заведёт товар под номером, которого не существует.
 */
class BarcodeValidatorTest {

    @Test
    fun `EAN-13 с верной контрольной цифрой проходит как есть`() {
        assertEquals("4600699500001", BarcodeValidator.normalize("4600699500001"))
    }

    @Test
    fun `одна перепутанная цифра отсекается`() {
        assertNull(BarcodeValidator.normalize("4600699500002"))
    }

    @Test
    fun `пробелы вокруг кода не мешают`() {
        assertEquals("4600699500001", BarcodeValidator.normalize("  4600699500001 "))
    }

    @Test
    fun `буквы и пустая строка — не код`() {
        assertNull(BarcodeValidator.normalize(""))
        assertNull(BarcodeValidator.normalize("4600-699-500001"))
        assertNull(BarcodeValidator.normalize("abc"))
    }

    @Test
    fun `нестандартная длина не принимается`() {
        assertNull(BarcodeValidator.normalize("12345"))
        assertNull(BarcodeValidator.normalize("46006995000011"))
    }

    @Test
    fun `UPC-A становится EAN-13 с ведущим нулём`() {
        // Один и тот же товар не должен заводиться в базе дважды под двумя записями.
        assertEquals("0036000291452", BarcodeValidator.normalize("036000291452"))
    }

    @Test
    fun `EAN-8 остаётся восьмизначным`() {
        assertEquals("96385074", BarcodeValidator.normalize("96385074"))
    }

    @Test
    fun `UPC-E разворачивается в EAN-13, когда формат известен`() {
        // 01234565 — сжатая запись UPC-A 012345000065.
        assertEquals("0012345000065", BarcodeValidator.normalizeUpcE("01234565"))
    }

    @Test
    fun `восемь цифр без указания формата читаются как EAN-8`() {
        // Тот же 01234565 проходит и как EAN-8 — и без подсказки распознавателя
        // выбирается именно он: на еде EAN-8 встречается несопоставимо чаще.
        assertEquals("01234565", BarcodeValidator.normalize("01234565"))
    }

    @Test
    fun `UPC-E с чужой системой нумерации не разворачивается`() {
        assertNull(BarcodeValidator.normalizeUpcE("51234565"))
    }
}
