package n7.kcalai.feature.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import n7.kcalai.model.FoodCandidate
import n7.kcalai.personal.MealGap

/**
 * Идея 4: вопрос про приём пищи, который человек, похоже, забыл записать.
 *
 * Формулировка намеренно нейтральная. «Вы не обедали» — это упрёк и повод удалить
 * приложение; «Обеда нет» — констатация, на которую есть три ответа в один тап
 * и четвёртый, закрывающий вопрос до завтра.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GapBanner(
    gap: MealGap,
    onPick: (FoodCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${mealName(gap.meal)} не записан"
            },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${mealName(gap.meal)} не записан",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "Обычно около ${formatHour(gap.typicalHour)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                TextButton(onClick = onDismiss) { Text("Пропустил") }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                gap.suggestions.forEach { candidate ->
                    AssistChip(
                        onClick = { onPick(candidate) },
                        label = {
                            Text(
                                candidate.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}
