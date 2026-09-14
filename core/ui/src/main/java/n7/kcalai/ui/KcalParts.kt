package n7.kcalai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Макрос как роль, а не как цвет: цвет у него разный на плитке, в тексте
 * и на тёмном пузырьке.
 */
enum class Macro { PROTEIN, FAT, CARB }

/** Буква для строки макросов и для компактных подписей. */
val Macro.letter: String
    get() = when (this) {
        Macro.PROTEIN -> "Б"
        Macro.FAT -> "Ж"
        Macro.CARB -> "У"
    }

/** «БЕЛКИ» — метка плитки. Углеводы сокращены: втроём они не помещаются. */
val Macro.tileLabel: String
    get() = when (this) {
        Macro.PROTEIN -> "БЕЛКИ"
        Macro.FAT -> "ЖИРЫ"
        Macro.CARB -> "УГЛЕВ."
    }

@Composable
fun Macro.colors(): MacroColors = when (this) {
    Macro.PROTEIN -> KcalTheme.colors.protein
    Macro.FAT -> KcalTheme.colors.fat
    Macro.CARB -> KcalTheme.colors.carb
}

/** Точка доминирующего макроса. Шесть точек — ровно столько, чтобы цвет читался. */
@Composable
fun MacroDot(color: Color, size: Dp = 6.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/**
 * Полоска заполнения.
 *
 * Дорожка полупрозрачная поверх своего фона, а не отдельным цветом: полосок
 * на экране бывает по шесть, и подобранные вручную дорожки разошлись бы
 * с фонами при первой же правке палитры.
 */
@Composable
fun MiniBar(
    fraction: Float,
    fill: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    trackAlpha: Float = 0.12f,
) {
    val radius = RoundedCornerShape(height / 2)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(radius)
            .background(KcalTheme.colors.text.copy(alpha = trackAlpha))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(radius)
                .background(fill)
        )
    }
}

/** Подпись капсом: дата, «сегодня», «обычно в это время», «сканирование». */
@Composable
fun CapsLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = KcalTheme.colors.text3,
) {
    Text(text.uppercase(), style = KcalTheme.type.caps, color = color, modifier = modifier)
}

/**
 * Чипс.
 *
 * Почти единственная кликабельная форма в приложении — и тап по ней всегда
 * что-то добавляет или открывает. Чипс несёт данные, поэтому человек всегда
 * видит, на что именно нажимает.
 */
@Composable
fun KcalChip(
    onClick: (() -> Unit)?,
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(KcalShapes.chip))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Моноширинная приписка к названию на чипсе: «· 150 г · 121». */
@Composable
fun ChipNumbers(text: String, color: Color, style: TextStyle = KcalTheme.type.time) {
    Text(text, style = style, color = color, modifier = Modifier.padding(start = 6.dp))
}

/** Кнопка с надписью — там, где действие нельзя назвать данными. */
@Composable
fun WideButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    val colors = KcalTheme.colors
    val background = when {
        !enabled -> colors.chip
        filled -> colors.bubble
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = KcalTheme.type.input,
            color = when {
                !enabled -> colors.text3
                filled -> colors.onBubble
                else -> colors.text
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
