package n7.kcalai.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Та же проверка, что в клиенте: одна перепутанная цифра не должна попадать в пул. */
class GtinTest {

    @Test
    fun `EAN-13 с верной контрольной цифрой проходит`() {
        assertTrue(Gtin.isValid("4600699500001"))
    }

    @Test
    fun `EAN-8 проходит`() {
        assertTrue(Gtin.isValid("96385074"))
    }

    @Test
    fun `перепутанная цифра, буквы и чужая длина отсекаются`() {
        assertFalse(Gtin.isValid("4600699500002"))
        assertFalse(Gtin.isValid("46006995000"))
        assertFalse(Gtin.isValid("abc"))
        assertFalse(Gtin.isValid(""))
    }

    /**
     * Длина и состав проверяются каждый сам по себе.
     *
     * Иначе одна проверка молча прикрывает отсутствие другой: «abc» отсекается
     * по длине, а одиннадцать цифр — случайно не сошедшейся суммой. Здесь оба
     * случая взяты так, чтобы пройти всё, кроме проверяемого правила.
     */
    @Test
    fun `длина и состав проверяются порознь`() {
        // Тринадцать знаков — длина верная, но среди них буква.
        assertFalse("буква в коде верной длины", Gtin.isValid("460069950000a"))
        // Двенадцать цифр — UPC-A, сервер его не принимает: в пул идёт
        // тринадцатизначный вид, нормализацию делает клиент.
        assertFalse("двенадцать цифр — не GTIN для сервера", Gtin.isValid("036000291452"))
    }
}
