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
        // Пустая строка и нецифры отсекаются каждая сама по себе: оба условия
        // проверяются до контрольной суммы, и подменять одно другим нельзя.
        assertNull(BarcodeValidator.normalize(""))
        assertNull(BarcodeValidator.normalize("   "))
        assertNull(BarcodeValidator.normalize("4600-699-500001"))
        assertNull(BarcodeValidator.normalize("abc"))
        // Тринадцать знаков верной длины, но с буквой: длина пройдёт, цифры — нет.
        assertNull(BarcodeValidator.normalize("460069950000a"))
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

    /**
     * Последняя значащая цифра говорит, сколько нулей выброшено и откуда, —
     * у каждой своя схема, и спутать их значит вернуть код другого товара.
     */
    @Test
    fun `каждая схема сжатия UPC-E разворачивается по-своему`() {
        assertEquals("0012200003453", BarcodeValidator.normalizeUpcE("01234523"))
        assertEquals("0012300000451", BarcodeValidator.normalizeUpcE("01234531"))
        assertEquals("0012340000053", BarcodeValidator.normalizeUpcE("01234543"))
    }

    @Test
    fun `восемь цифр без указания формата читаются как EAN-8`() {
        // Тот же 01234565 проходит и как EAN-8 — и без подсказки распознавателя
        // выбирается именно он: на еде EAN-8 встречается несопоставимо чаще.
        assertEquals("01234565", BarcodeValidator.normalize("01234565"))
    }

    @Test
    fun `UPC-E с чужой системой нумерации не разворачивается`() {
        // Схема сжатия определена только для систем 0 и 1. «50000005» по ней
        // разворачивается в код, который проходит и контрольную сумму, —
        // то есть выглядит настоящим товаром, которым не является.
        assertNull(BarcodeValidator.normalizeUpcE("50000005"))
        // Та же развёртка для системы 0 законна — значит, отсекает именно
        // первая цифра, а не сумма заодно.
        assertEquals("0000000000000", BarcodeValidator.normalizeUpcE("00000000"))
    }

    @Test
    fun `UPC-E с битой контрольной цифрой не разворачивается`() {
        // Контрольная цифра у UPC-E своя не бывает — там стоит цифра от UPC-A,
        // и проверять её надо уже на развёрнутом коде.
        assertNull(BarcodeValidator.normalizeUpcE("01234564"))
        assertNull(BarcodeValidator.normalizeUpcE("0123456a"))
    }
}
