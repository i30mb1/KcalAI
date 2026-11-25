package n7.kcalai.feature.diary

import kotlin.test.Test
import kotlin.test.assertEquals

/** Граммы на 100 г с дробью: «2,9» пишется и с запятой, и с точкой — клавиатуры разные. */
class DecimalInputTest {

    @Test
    fun `точка становится запятой`() {
        assertEquals("12,5", decimalInput("12.5"))
        assertEquals("12,5", decimalInput("12,5"))
    }

    @Test
    fun `вторая запятая не ставится`() {
        assertEquals("1,23", decimalInput("1,2,3"))
    }

    /** Цифры хранятся в сотых грамма: третий знак после запятой ничего бы не значил. */
    @Test
    fun `после запятой не больше двух знаков`() {
        assertEquals("7,55", decimalInput("7.555"))
    }

    @Test
    fun `буквы и пробелы отбрасываются`() {
        assertEquals("85", decimalInput("8 5г"))
    }
}
