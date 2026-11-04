package n7.kcalai.feature.scanner

import androidx.compose.ui.text.input.KeyboardType

/**
 * Какое число вписывают: от этого зависят клавиатура, запятая, длина и ведущий ноль.
 *
 * @param whole сколько цифр до запятой
 */
internal enum class NumberKind(val whole: Int, val keyboard: KeyboardType) {
    /**
     * Четыре цифры, хотя больше 900 ккал не бывает: «1330» — это килоджоули,
     * перепутанные с калориями. Проверка покажет их ошибкой, а молча обрезанное
     * «133» было бы похоже на правду.
     */
    KCAL(whole = 4, keyboard = KeyboardType.Number),

    /** Больше ста граммов в ста граммах не помещается. */
    GRAMS(whole = 3, keyboard = KeyboardType.Decimal),

    /** EAN-13 — самый длинный из кодов на еде. Ведущий ноль — часть кода. */
    CODE(whole = 13, keyboard = KeyboardType.Number),
}

/**
 * Что остаётся в поле от того, что пришло с клавиатуры.
 *
 * Клавиатуры разные: где-то запятая, где-то точка, а вставить можно что угодно.
 * Запятая есть только у граммов: калории на этикетке целые, а в коде дробей не бывает.
 */
internal fun NumberKind.clean(input: String): String {
    if (this == NumberKind.CODE) return input.digits().take(whole)

    val separator = if (this == NumberKind.GRAMS) input.indexOfFirst { it == ',' || it == '.' } else -1
    val digits = (if (separator < 0) input else input.take(separator)).digits()
    // «05 г» на пачке не пишут: ноль впереди — это начало дроби или промах.
    val integer = digits.trimStart('0').ifEmpty { digits.take(1) }.take(whole)
    if (separator < 0) return integer

    val fraction = input.drop(separator + 1).digits().take(FRACTION_DIGITS)
    return "${integer.ifEmpty { "0" }},$fraction"
}

private fun String.digits(): String = filter { it in '0'..'9' }

/** Точнее сотых на пачке не пишут, а модель хранит именно сотые грамма. */
private const val FRACTION_DIGITS = 2
