package n7.kcalai.feature.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import n7.kcalai.personal.DayPlan
import n7.kcalai.personal.PlanOption

/**
 * Идея 5: чем закрыть остаток дня.
 *
 * Ничего не советует и ничего не придумывает: варианты собраны из того, что человек
 * уже ест, порции — его собственные медианы, а отбор это точный перебор по остатку
 * калорий с максимизацией недобранного белка. Тап добавляет вариант целиком.
 */
@Composable
fun RemainingCard(plan: DayPlan, onPick: (PlanOption) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                buildString {
                    append("Осталось ")
                    append(plan.remainingKcal)
                    append(" ккал")
                    if (plan.remainingProtCg > 0) {
                        append(" и ")
                        append(formatCentigrams(plan.remainingProtCg))
                        append(" г белка")
                    }
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            plan.options.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                OptionRow(option) { onPick(option) }
            }
        }
    }
}

@Composable
private fun OptionRow(option: PlanOption, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                option.items.joinToString(" + ") { it.displayName },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                option.items.joinToString(" · ") { formatGrams(it.servingG ?: 0) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${option.totals.kcal} ккал", style = MaterialTheme.typography.titleSmall)
            Text(
                "Б ${formatCentigrams(option.totals.protCg)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
