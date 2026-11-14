package n7.kcalai.feature.scanner

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Что остаётся в поле от того, что пришло с системной клавиатуры.
 *
 * Клавиатуры разные: где-то запятая, где-то точка, а вставить можно что угодно.
 * Поле обязано держать только то, что бывает в числах с этикетки.
 */
class NumberInputTest {

    @Test
    fun `цифры остаются как набраны`() {
        assertEquals("121", NumberKind.KCAL.clean("121"))
        assertEquals("12,4", NumberKind.GRAMS.clean("12,4"))
    }

    /** Цифровая клавиатура часто даёт только точку, а в граммах пишем запятую, как на пачке. */
    @Test
    fun `точка становится запятой`() {
        assertEquals("12,4", NumberKind.GRAMS.clean("12.4"))
    }

    /** «05 г» на пачке не пишут: ноль впереди — это начало дроби или промах. */
    @Test
    fun `ноль впереди уступает следующей цифре`() {
        assertEquals("5", NumberKind.KCAL.clean("05"))
        assertEquals("7", NumberKind.GRAMS.clean("07"))
        assertEquals("0,5", NumberKind.GRAMS.clean("0,5"))
        assertEquals("0", NumberKind.GRAMS.clean("00"))
    }

    /** Ноль — тоже ответ: у масла белков и правда нет. */
    @Test
    fun `одинокий ноль остаётся`() {
        assertEquals("0", NumberKind.GRAMS.clean("0"))
        assertEquals("0", NumberKind.KCAL.clean("0"))
    }

    @Test
    fun `запятая на пустом поле начинает дробь с нуля`() {
        assertEquals("0,5", NumberKind.GRAMS.clean(",5"))
    }

    @Test
    fun `вторая запятая не ставится`() {
        assertEquals("1,23", NumberKind.GRAMS.clean("1,2,3"))
    }

    /** Калории на этикетке целые, и дробь в них — промах пальцем. */
    @Test
    fun `в калориях запятой нет`() {
        assertEquals("125", NumberKind.KCAL.clean("12,5"))
    }

    /** Больше ста граммов в ста граммах не бывает, а точнее сотых на пачке не пишут. */
    @Test
    fun `граммы — до трёх цифр до запятой и двух после`() {
        assertEquals("100", NumberKind.GRAMS.clean("1000"))
        assertEquals("3,33", NumberKind.GRAMS.clean("3,333"))
    }

    /**
     * Четыре цифры, хотя калорий больше 900 не бывает: «1330» — это килоджоули,
     * перепутанные с калориями, и проверка должна их показать, а не получить
     * молча обрезанное «133», похожее на правду.
     */
    @Test
    fun `калории — до четырёх цифр`() {
        assertEquals("1330", NumberKind.KCAL.clean("13300"))
    }

    /** У штрих-кода ведущий ноль — часть кода: UPC начинается с нуля. */
    @Test
    fun `штрих-код хранит ведущие нули и не длиннее тринадцати цифр`() {
        assertEquals("0012345678905", NumberKind.CODE.clean("0012345678905"))
        assertEquals("4607001771234", NumberKind.CODE.clean("46070017712349"))
        assertEquals("46", NumberKind.CODE.clean("4,6"))
    }

    /** Вставить из буфера можно что угодно: «12,4 г», код с пробелами. */
    @Test
    fun `всё кроме цифр не встаёт`() {
        assertEquals("12,4", NumberKind.GRAMS.clean("12,4 г"))
        assertEquals("121", NumberKind.KCAL.clean("121 ккал"))
        assertEquals("4607001771234", NumberKind.CODE.clean("4 607001 771234"))
    }

    /** Стирают по одному знаку, и запятая на конце — ещё не ошибка. */
    @Test
    fun `недописанное и пустое остаются как есть`() {
        assertEquals("12,", NumberKind.GRAMS.clean("12,"))
        assertEquals("", NumberKind.GRAMS.clean(""))
    }
}
