package n7.kcalai.feature.diary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlin.math.abs
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.repositories.DaySummary
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.Macro
import n7.kcalai.ui.MiniBar
import n7.kcalai.ui.colors
import n7.kcalai.ui.tileLabel
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme

/**
 * Шапка: дата, остаток, три макроса и неделя.
 *
 * Закреплена, а не уезжает со лентой, и сжимается при прокрутке. Это разрешение
 * старого противоречия: полная шапка занимает треть небольшого экрана, но остаток
 * до цели — единственное число, за которым трекер и открывают, и терять его
 * из вида при прокрутке нельзя. Поэтому при скролле от неё остаётся одна строка.
 *
 * Полосы прогресса калорий здесь нет намеренно: остаток уже стоит крупным числом,
 * а полоса повторяла бы ту же величину второй раз, отбирая место у недели.
 */
@Composable
fun Header(
    date: LocalDate,
    totals: NutrimentTotals,
    goal: DailyGoalEntity?,
    week: List<DaySummary>,
    collapsed: Boolean,
    onOpenGoal: (GoalField) -> Unit,
) {
    val colors = KcalTheme.colors

    Column(Modifier.fillMaxWidth().background(colors.bg).statusBarsPadding()) {
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RemainingRow(date, totals, goal, onOpenGoal)
                MacroTiles(totals, goal, onOpenGoal)
                WeekChart(week)
            }
        }

        AnimatedVisibility(
            visible = collapsed,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            CompactRow(date, totals, goal)
        }

        HorizontalDivider(color = colors.line)
    }
}

/**
 * Дата, заголовок и остаток.
 *
 * Крупным числом стоит остаток, а не съеденное, и это главное решение экрана:
 * трекер открывают с вопросом «сколько мне ещё можно», а не «сколько я уже съел».
 * Второе выводится из первого, обратное — нет.
 */
@Composable
private fun RemainingRow(
    date: LocalDate,
    totals: NutrimentTotals,
    goal: DailyGoalEntity?,
    onOpenGoal: (GoalField) -> Unit,
) {
    val colors = KcalTheme.colors
    val type = KcalTheme.type
    val target = goal?.kcal?.takeIf { it > 0 }
    val remaining = target?.let { it - totals.kcal }
    val over = remaining != null && remaining < 0

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CapsLabel(formatDateCaps(date))
            Text("Дневник", style = type.title, color = colors.text)
        }

        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = (remaining?.let { abs(it) } ?: totals.kcal).toString(),
                    style = type.hero,
                    // Перебор выделяется цветом, но остаётся числом, а не упрёком:
                    // подпись рядом уже сказала, что произошло.
                    color = if (over) colors.error else colors.text,
                )
                Text(
                    text = when {
                        remaining == null -> "съедено"
                        over -> "перебор"
                        else -> "осталось"
                    },
                    style = type.label,
                    color = if (over) colors.error else colors.text2,
                    modifier = Modifier.padding(start = 5.dp, bottom = 3.dp),
                )
            }

            Text(
                text = if (target == null) "задать цель ›" else "${totals.kcal} / $target ›",
                style = type.macro,
                color = colors.text3,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onOpenGoal(GoalField.KCAL) }
                    .padding(top = 4.dp, bottom = 2.dp, start = 4.dp, end = 2.dp),
            )
        }
    }
}

/**
 * Три плитки макросов.
 *
 * Тап открывает шит цели курсором в этом поле — и это единственный способ туда
 * попасть, кроме тапа по остатку. Кнопки цели в шапке больше нет: она открывала
 * ровно то же, но занимала место и не говорила, что покажет.
 */
@Composable
private fun MacroTiles(
    totals: NutrimentTotals,
    goal: DailyGoalEntity?,
    onOpenGoal: (GoalField) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        MacroTile(Macro.PROTEIN, totals.protCg, goal?.prot, GoalField.PROT, onOpenGoal, Modifier.weight(1f))
        MacroTile(Macro.FAT, totals.fatCg, goal?.fat, GoalField.FAT, onOpenGoal, Modifier.weight(1f))
        MacroTile(Macro.CARB, totals.carbCg, goal?.carb, GoalField.CARB, onOpenGoal, Modifier.weight(1f))
    }
}

@Composable
private fun MacroTile(
    macro: Macro,
    eatenCg: Int,
    goalGrams: Int?,
    field: GoalField,
    onOpenGoal: (GoalField) -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = KcalTheme.type
    val palette = macro.colors()
    val target = goalGrams?.takeIf { it > 0 }
    val eaten = eatenCg / CENTIGRAMS_PER_GRAM

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(palette.tile)
            .clickable { onOpenGoal(field) }
            .padding(horizontal = 11.dp, vertical = 10.dp)
            .semantics {
                contentDescription = buildString {
                    append(macro.tileLabel.lowercase())
                    append(" $eaten")
                    if (target != null) append(" из $target")
                    append(" грамм")
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(macro.tileLabel, style = type.capsTile, color = palette.text)

        Row(verticalAlignment = Alignment.Bottom) {
            Text("$eaten", style = type.number, color = KcalTheme.colors.text)
            // Без цели знаменателя нет вовсе: «96/0» читалось бы как перебор,
            // а «96» — как то, что и есть, съеденные граммы.
            if (target != null) {
                Text(
                    "/$target",
                    style = type.number.copy(fontWeight = FontWeight.W500),
                    color = palette.text,
                )
            }
        }

        if (target != null) {
            MiniBar(fraction = eaten.toFloat() / target, fill = palette.fill)
        }
    }
}

/** Сжатая шапка: дата и остаток. Всё остальное при прокрутке уступает место ленте. */
@Composable
private fun CompactRow(date: LocalDate, totals: NutrimentTotals, goal: DailyGoalEntity?) {
    val colors = KcalTheme.colors
    val type = KcalTheme.type
    val target = goal?.kcal?.takeIf { it > 0 }
    val remaining = target?.let { it - totals.kcal }
    val over = remaining != null && remaining < 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsLabel(formatDateCaps(date), Modifier.weight(1f))
        Text(
            text = (remaining?.let { abs(it) } ?: totals.kcal).toString(),
            style = type.number,
            color = if (over) colors.error else colors.text,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = when {
                remaining == null -> "съедено"
                over -> "перебор"
                else -> "осталось"
            },
            style = type.label,
            color = if (over) colors.error else colors.text2,
        )
    }
}

private const val CENTIGRAMS_PER_GRAM = 100
