package n7.kcalai.feature.diary

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
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.Nutriments
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.MacroColors

/** Макрос как роль, а не как цвет: цвет у него разный на плитке, в тексте и на пузырьке. */
enum class Macro { PROTEIN, FAT, CARB }

/** Буква для строки макросов пузырька и для плитки. */
val Macro.letter: String
    get() = when (this) {
        Macro.PROTEIN -> "Б"
        Macro.FAT -> "Ж"
        Macro.CARB -> "У"
    }

/** «БЕЛКИ» — метка плитки в шапке. Углеводы сокращены: втроём они не помещаются. */
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

/**
 * Главный макрос порции — по граммам, а не по калориям.
 *
 * По калориям победителем почти всегда выходил бы жир: в нём девять килокалорий
 * на грамм против четырёх. Точка на чипсе отвечает на вопрос «из чего это в основном
 * состоит», и граммы отвечают на него честнее.
 */
fun dominantMacro(totals: NutrimentTotals): Macro =
    dominantMacro(totals.protCg, totals.fatCg, totals.carbCg)

/** То же для продукта, у которого ещё нет веса: доли от веса не зависят. */
fun dominantMacro(nutriments: Nutriments): Macro =
    dominantMacro(nutriments.prot100, nutriments.fat100, nutriments.carb100)

private fun dominantMacro(prot: Int, fat: Int, carb: Int): Macro = when {
    prot >= fat && prot >= carb -> Macro.PROTEIN
    fat >= carb -> Macro.FAT
    else -> Macro.CARB
}

/** Точка доминирующего макроса. Шесть точек — ровно столько, чтобы цвет читался. */
@Composable
fun MacroDot(color: Color, size: Dp = 6.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/**
 * Мини-бар на плитке макроса.
 *
 * Дорожка полупрозрачная поверх плитки, а не отдельным цветом: плиток три,
 * и три подобранные вручную дорожки разошлись бы с фонами при любой правке палитры.
 */
@Composable
fun MiniBar(fraction: Float, fill: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(KcalTheme.colors.text.copy(alpha = 0.12f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(fill)
        )
    }
}

/** Подпись капсом: дата, «сегодня», «обычно в это время». */
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
 * Единственная кликабельная форма на экране — и тап по ней всегда что-то добавляет
 * или открывает. Кнопок с надписями здесь нет вовсе: чипс несёт данные, поэтому
 * человек всегда видит, на что именно нажимает.
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
