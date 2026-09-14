package n7.kcalai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Строка ссылки — ключ, по которому история узнаёт продукт.
 *
 * Формат хранится в дневнике и в журнале подтверждений, и разъехаться
 * с самим собой он не вправе: иначе всё, что модели знали о продукте, пропадает.
 */
class FoodRefTest {

    @Test
    fun `ссылка переживает сериализацию туда и обратно`() {
        val refs = listOf(FoodRef.Barcode("4600699500001"), FoodRef.Generic(12), FoodRef.User(7))

        for (ref in refs) {
            assertEquals(ref, parseFoodRef(ref.serialize()))
        }
    }

    @Test
    fun `формат строки стабилен`() {
        assertEquals("barcode:4600699500001", FoodRef.Barcode("4600699500001").serialize())
        assertEquals("generic:12", FoodRef.Generic(12).serialize())
        assertEquals("user:7", FoodRef.User(7).serialize())
    }

    @Test
    fun `мусор в строке — это null, а не исключение`() {
        assertNull(parseFoodRef(null))
        assertNull(parseFoodRef(""))
        assertNull(parseFoodRef("generic"))
        assertNull(parseFoodRef("generic:"))
        assertNull(parseFoodRef("generic:abc"))
        assertNull(parseFoodRef("photo:1"))
        assertNull(parseFoodRef(":1"))
    }
}
