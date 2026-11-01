package n7.kcalai.feature.scanner

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Цифровая панель вместо системной клавиатуры: что получается в поле от нажатий.
 *
 * Системная клавиатура закрывала полэкрана — вместе с пачкой в кадре — и всё равно
 * предлагала буквы. Здесь только цифры, запятая там, где бывают дроби, и «стереть».
 */
class DigitPadTest {

    /** Нажатия по порядку: цифры, «,» — запятая, «<» — стереть. */
    private fun typed(number: PadNumber, keys: String, start: String = ""): String =
        keys.fold(start) { text, key ->
            val pressed = when (key) {
                ',' -> PadKey.Comma
                '<' -> PadKey.Erase
                else -> PadKey.Digit(key.digitToInt())
            }
            text.press(pressed, number)
        }

    @Test
    fun `цифры встают подряд`() {
        assertEquals("121", typed(PadNumber.KCAL, "121"))
        assertEquals("12,4", typed(PadNumber.GRAMS, "12,4"))
    }

    /** «05 г» на пачке не пишут: ноль впереди — это начало дроби или опечатка. */
    @Test
    fun `ноль впереди уступает следующей цифре`() {
        assertEquals("5", typed(PadNumber.KCAL, "05"))
        assertEquals("7", typed(PadNumber.GRAMS, "07"))
        assertEquals("0,5", typed(PadNumber.GRAMS, "0,5"))
    }

    @Test
    fun `запятая на пустом поле начинает дробь с нуля`() {
        assertEquals("0,5", typed(PadNumber.GRAMS, ",5"))
    }

    @Test
    fun `вторая запятая не ставится`() {
        assertEquals("1,23", typed(PadNumber.GRAMS, "1,2,3"))
    }

    /** Калории на этикетке целые, и дробь в них — промах пальцем. */
    @Test
    fun `в калориях запятой нет`() {
        assertEquals("125", typed(PadNumber.KCAL, "12,5"))
    }

    /** Больше ста граммов в ста граммах не бывает, а точнее сотых на пачке не пишут. */
    @Test
    fun `граммы — до трёх цифр до запятой и двух после`() {
        assertEquals("100", typed(PadNumber.GRAMS, "1000"))
        assertEquals("3,33", typed(PadNumber.GRAMS, "3,333"))
    }

    /**
     * Четыре цифры, хотя калорий больше 900 не бывает: «1330» — это килоджоули,
     * перепутанные с калориями, и проверка должна их показать, а не получить
     * молча обрезанное «133», похожее на правду.
     */
    @Test
    fun `калории — до четырёх цифр`() {
        assertEquals("1330", typed(PadNumber.KCAL, "13300"))
    }

    /** У штрих-кода ведущий ноль — часть кода: UPC начинается с нуля. */
    @Test
    fun `штрих-код хранит ведущие нули и не длиннее тринадцати цифр`() {
        assertEquals("0012345678905", typed(PadNumber.CODE, "0012345678905"))
        assertEquals("4607001771234", typed(PadNumber.CODE, "46070017712349"))
        assertEquals("46", typed(PadNumber.CODE, "4,6"))
    }

    @Test
    fun `стереть убирает последний знак`() {
        assertEquals("12,", typed(PadNumber.GRAMS, "<", start = "12,4"))
        assertEquals("12", typed(PadNumber.GRAMS, "<<", start = "12,4"))
        assertEquals("", typed(PadNumber.GRAMS, "<"))
    }
}
