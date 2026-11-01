package n7.kcalai.feature.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme

/**
 * Цифры вместо системной клавиатуры.
 *
 * Системная закрывала полэкрана вместе с пачкой в кадре и всё равно предлагала
 * буквы. Здесь ровно то, что бывает в числах с этикетки: десять цифр, «стереть»
 * и — только у граммов — запятая. Долгое «стереть» очищает поле целиком.
 */
@Composable
internal fun DigitPad(
    number: PadNumber,
    onKey: (PadKey) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val press: (PadKey) -> Unit = { key ->
        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        onKey(key)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PAD_GAP)) {
        DIGIT_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PAD_GAP)) {
                row.forEach { digit ->
                    PadButton(Modifier.weight(1f), onClick = { press(PadKey.Digit(digit)) }) { PadText("$digit") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PAD_GAP)) {
            if (number == PadNumber.GRAMS) {
                PadButton(Modifier.weight(1f), onClick = { press(PadKey.Comma) }) { PadText(",") }
            } else {
                Spacer(Modifier.weight(1f))
            }
            PadButton(Modifier.weight(1f), onClick = { press(PadKey.Digit(0)) }) { PadText("0") }
            PadButton(
                modifier = Modifier.weight(1f),
                onClick = { press(PadKey.Erase) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClear()
                },
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = "Стереть",
                    tint = KcalTheme.colors.text,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun PadButton(
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .height(PAD_KEY_HEIGHT)
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(KcalTheme.colors.chip)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun PadText(text: String) {
    Text(text, style = KcalTheme.type.number.copy(fontSize = 22.sp), color = KcalTheme.colors.text)
}

private val DIGIT_ROWS = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
private val PAD_GAP = 6.dp

/** Под большой палец, но так, чтобы четыре ряда не съедали камеру целиком. */
private val PAD_KEY_HEIGHT = 50.dp

/** Кнопка цифровой панели. */
internal sealed interface PadKey {
    data class Digit(val digit: Int) : PadKey
    data object Comma : PadKey
    data object Erase : PadKey
}

/**
 * Что набирается: от этого зависят запятая, длина и ведущий ноль.
 *
 * @param whole сколько цифр до запятой
 */
internal enum class PadNumber(val whole: Int) {
    /**
     * Четыре цифры, хотя больше 900 ккал не бывает: «1330» — это килоджоули,
     * перепутанные с калориями. Проверка покажет их ошибкой, а молча обрезанное
     * «133» было бы похоже на правду.
     */
    KCAL(whole = 4),

    /** Больше ста граммов в ста граммах не помещается. */
    GRAMS(whole = 3),

    /** EAN-13 — самый длинный из кодов на еде. Ведущий ноль — часть кода. */
    CODE(whole = 13),
}

/**
 * Поле после нажатия [key].
 *
 * Запятая есть только у граммов: калории на этикетке целые, а в коде дробей
 * не бывает. Лишнее нажатие ничего не ломает — его просто нет.
 */
internal fun String.press(key: PadKey, number: PadNumber): String = when (key) {
    PadKey.Erase -> dropLast(1)
    PadKey.Comma -> when {
        number != PadNumber.GRAMS || contains(',') -> this
        isEmpty() -> "0,"
        else -> "$this,"
    }
    is PadKey.Digit -> withDigit('0' + key.digit, number)
}

private fun String.withDigit(digit: Char, number: PadNumber): String {
    if (number == PadNumber.CODE) return if (length < number.whole) this + digit else this

    // «05 г» на пачке не пишут: ноль впереди — это начало дроби или промах.
    if (this == "0") return digit.toString()

    val comma = indexOf(',')
    val room = if (comma < 0) length < number.whole else length - comma - 1 < FRACTION_DIGITS
    return if (room) this + digit else this
}

/** Точнее сотых на пачке не пишут, а модель хранит именно сотые грамма. */
private const val FRACTION_DIGITS = 2
