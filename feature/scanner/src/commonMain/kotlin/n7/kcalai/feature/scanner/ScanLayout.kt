package n7.kcalai.feature.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import n7.kcalai.ui.KcalTheme

/*
 * Размеры и раскладка окна съёмки — общие для платформ.
 *
 * Своя у платформ только камера: превью, кадры и штрих-код. Где стоит рамка,
 * сколько места занимает панель и как выглядит кнопка — одно и то же, и
 * расходиться этому незачем: окно на обеих платформах одно и то же окно.
 */

/** Что стоит внизу окна. Анимируется смена вида, а не каждое изменение внутри. */
internal sealed interface Bottom {
    data class Hint(val searching: Boolean) : Bottom
    data class Code(val searching: Boolean) : Bottom
    data class Card(val state: ScanState) : Bottom
}

internal class ViewfinderWindow(val top: Dp, val height: Dp)

/**
 * Где окно видоискателя: полоса под штрих-код (fit = 0) или окно под таблицу (fit = 1).
 *
 * Полоса — чуть выше середины свободного места: штрих-код узкий, а не таблица. Окно
 * под таблицу — от персонажей до низа кадра: кадр стоит по ширине от верха, и ниже
 * него только карточка.
 *
 * @param codePanel что закрывает низ на шаге кода: ввод цифр с клавиатурой
 * @param cardPanel что закрывает низ в карточке
 */
internal fun viewfinderWindow(
    width: Dp,
    height: Dp,
    shelfTop: Dp,
    codePanel: Dp,
    cardPanel: Dp,
    fit: Float,
): ViewfinderWindow {
    val free = height - codePanel
    val codeHeight = minOf(height * CODE_WINDOW_SHARE, free - shelfTop - 12.dp).coerceAtLeast(MIN_WINDOW)
    val codeTop = maxOf((free - codeHeight) * 0.42f, shelfTop)

    val tableTop = shelfTop + SHELF_HEIGHT + 10.dp
    val tableBottom = minOf(width * 4f / 3f, height - cardPanel) - 12.dp
    val tableHeight = (tableBottom - tableTop).coerceAtLeast(MIN_WINDOW)

    return ViewfinderWindow(top = lerp(codeTop, tableTop, fit), height = lerp(codeHeight, tableHeight, fit))
}

@Composable
internal fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(KcalTheme.colors.surface.copy(alpha = 0.92f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = KcalTheme.type.label, color = KcalTheme.colors.text)
    }
}

/** Верхняя строка: крестик 44 dp и отступы вокруг. Персонажи стоят сразу под ней. */
internal val TOP_BAR_HEIGHT = 56.dp

/** Высота полосы под штрих-код — доля экрана. Код — узкая полоса, а не таблица. */
internal const val CODE_WINDOW_SHARE = 0.22f

/** Сколько обычно занимает карточка, пока читает камера: окно под таблицу — выше неё. */
internal val READING_PANEL_HEIGHT = 300.dp

/** Сколько занимает карточка над клавиатурой: название и окошки чисел. */
internal val TYPING_PANEL_HEIGHT = 176.dp

/** Сколько занимает ввод штрих-кода над клавиатурой: заголовок, цифры и две кнопки. */
internal val CODE_PANEL_HEIGHT = 216.dp

/** Меньше окно не бывает даже на самом низком экране: в него должна влезать строка. */
internal val MIN_WINDOW = 96.dp

/** Переход от кода к этикетке: камера, рамка и приближение — одним движением. */
internal const val FIT_MS = 650

internal const val PANEL_MS = 320

internal const val TICK_MS = 1_000L

/** Пауза перед поиском по названию: дольше смены кадра, короче, чем человек ждёт подсказки. */
internal const val MATCH_DELAY_MS = 700L
