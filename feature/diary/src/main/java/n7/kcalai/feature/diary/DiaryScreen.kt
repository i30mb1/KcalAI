package n7.kcalai.feature.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.totals
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.Nutriments
import n7.kcalai.personal.PlanOption
import n7.kcalai.resolver.ResolvedItem

@Composable
fun DiaryRoute(viewModel: DiaryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DiaryScreen(
        state = state,
        onInputChange = viewModel::onInputChange,
        onPick = { item -> viewModel.onPick(item) },
        onPickCandidate = { candidate -> viewModel.onPickPrediction(candidate) },
        onPickForMeal = viewModel::onPickPrediction,
        onPickPlan = { option -> option.items.forEach { viewModel.onPickPrediction(it) } },
        onDismissGap = viewModel::onDismissMealGap,
        onDeleteEntry = viewModel::onDeleteEntry,
        onEditEntry = viewModel::onStartEditGrams,
        onOpenGoal = viewModel::onOpenGoal,
        onOpenWeight = viewModel::onOpenWeight,
    )

    when (val overlay = state.overlay) {
        Overlay.None -> Unit

        Overlay.Goal -> GoalDialog(
            goal = state.goal,
            tdee = state.personal.tdee,
            onConfirm = viewModel::onSetGoal,
            onDismiss = viewModel::onDismissOverlay,
        )

        Overlay.Weight -> WeightDialog(
            onConfirm = viewModel::onLogWeight,
            onDismiss = viewModel::onDismissOverlay,
        )

        is Overlay.EditGrams -> GramsEditor(
            entry = overlay.entry,
            onConfirm = { grams -> viewModel.onEditGrams(overlay.entry.id, grams) },
            onDismiss = viewModel::onDismissOverlay,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    state: DiaryUiState,
    onInputChange: (String) -> Unit,
    onPick: (ResolvedItem) -> Unit,
    onPickCandidate: (FoodCandidate) -> Unit,
    onPickForMeal: (FoodCandidate, MealType) -> Unit,
    onPickPlan: (PlanOption) -> Unit,
    onDismissGap: (MealType) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onEditEntry: (DiaryEntryEntity) -> Unit,
    onOpenGoal: () -> Unit,
    onOpenWeight: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Сегодня", style = MaterialTheme.typography.titleLarge)
                        Text(
                            formatDate(state.date),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenWeight) {
                        Icon(Icons.Default.MonitorWeight, contentDescription = "Записать вес")
                    }
                    IconButton(onClick = onOpenGoal) {
                        Icon(Icons.Default.Flag, contentDescription = "Цель на день")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TotalsHeader(state.totals, state.goal)
            HorizontalDivider()

            state.personal.mealGap?.let { gap ->
                GapBanner(
                    gap = gap,
                    onPick = { candidate -> onPickForMeal(candidate, gap.meal) },
                    onDismiss = { onDismissGap(gap.meal) },
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (state.entries.isEmpty()) {
                    item { EmptyHint() }
                } else {
                    items(state.entries, key = { it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            onEdit = { onEditEntry(entry) },
                            onDelete = { onDeleteEntry(entry.id) },
                        )
                    }
                }

                state.personal.dayPlan?.let { plan ->
                    item { RemainingCard(plan, onPickPlan) }
                }
            }

            InputArea(
                state = state,
                onInputChange = onInputChange,
                onPick = onPick,
                onPickCandidate = onPickCandidate,
            )
        }
    }
}

@Composable
private fun TotalsHeader(totals: NutrimentTotals, goal: DailyGoalEntity?) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (goal == null) "${totals.kcal}" else "${totals.kcal} / ${goal.kcal}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "ккал за день",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Macro("Б", totals.protCg)
            Macro("Ж", totals.fatCg)
            Macro("У", totals.carbCg)
        }

        // Прогресс не ограничивается единицей намеренно: перебор надо видеть,
        // а полоса, упёршаяся в край, врёт о том, насколько именно перебрал.
        if (goal != null && goal.kcal > 0) {
            LinearProgressIndicator(
                progress = { (totals.kcal.toFloat() / goal.kcal).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Macro(label: String, centigrams: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(start = 20.dp),
    ) {
        Text(formatCentigrams(centigrams), style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Пока пусто",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "Напишите блюдо и вес — например «гречка 200» — и выберите из предложенного",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Тап по строке правит вес. Это и удобство, и самый чистый сигнал для памяти порций. */
@Composable
private fun EntryRow(entry: DiaryEntryEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    val totals = entry.totals()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatGrams(entry.grams)} · Б ${formatCentigrams(totals.protCg)} · " +
                    "Ж ${formatCentigrams(totals.fatCg)} · У ${formatCentigrams(totals.carbCg)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("${totals.kcal} ккал", style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Удалить ${entry.displayName}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputArea(
    state: DiaryUiState,
    onInputChange: (String) -> Unit,
    onPick: (ResolvedItem) -> Unit,
    onPickCandidate: (FoodCandidate) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
            if (state.searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (state.suggestions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.suggestions.forEach { item ->
                        SuggestionChip(item) { onPick(item) }
                    }
                }
                HorizontalDivider()
            } else if (state.showPredictions) {
                PredictionRow(state.personal.predictions, onPickCandidate)
                HorizontalDivider()
            } else if (state.nothingFound) {
                Text(
                    "Ничего не нашлось — попробуйте назвать блюдо иначе",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                HorizontalDivider()
            }

            // Кнопки отправки нет намеренно: добавляет только тап по чипсу.
            // Вторая точка входа означала бы выбор «первого попавшегося» вслепую —
            // ровно того, от чего предложения и защищают.
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                placeholder = { Text("Что съели?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        }
    }
}

/**
 * Идея 1: что человек, скорее всего, съест прямо сейчас.
 *
 * Показывается на пустом поле — там, где раньше не было ничего. Модель биграмм
 * над личной историей знает, что после овсянки идёт кофе с молоком, и превращает
 * самый частый сценарий трекинга из набора текста в один тап.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PredictionRow(predictions: List<FoodCandidate>, onPick: (FoodCandidate) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            "Обычно в это время",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            predictions.forEach { candidate ->
                AssistChip(
                    onClick = { onPick(candidate) },
                    label = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                candidate.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatGrams(candidate.servingG ?: 0),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * Предложение: чем может быть набранное блюдо и сколько это в граммах.
 *
 * Нажатие сразу кладёт позицию в дневник — отдельного подтверждения нет,
 * выбор из списка и есть подтверждение.
 */
@Composable
private fun SuggestionChip(item: ResolvedItem, onClick: () -> Unit) {
    val candidate = item.candidate ?: return

    AssistChip(
        onClick = onClick,
        colors = AssistChipDefaults.assistChipColors(
            labelColor = MaterialTheme.colorScheme.onSurface,
        ),
        label = {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(
                    candidate.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatGrams(item.grams)} · ${candidate.nutriments.kcalFor(item.grams)} ккал",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

private fun Nutriments.kcalFor(grams: Int): Int =
    ((kcal100.toLong() * grams + 50) / 100).toInt()
