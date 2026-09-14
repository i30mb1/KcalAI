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
}
