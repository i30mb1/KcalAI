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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import kotlin.math.abs
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.totals
import n7.kcalai.feature.scanner.LabelScannerDialog
import n7.kcalai.feature.scanner.ScannerDialog
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
        onPickScanned = viewModel::onPickScanned,
        onOpenScan = viewModel::onOpenScan,
        onPickCandidate = { candidate -> viewModel.onPickPrediction(candidate) },
        onPickForMeal = viewModel::onPickPrediction,
        onPickPlan = { option -> option.items.forEach { viewModel.onPickPrediction(it) } },
        onDismissGap = viewModel::onDismissMealGap,
        onDeleteEntry = viewModel::onDeleteEntry,
        onEditEntry = viewModel::onStartEditGrams,
        onOpenGoal = viewModel::onOpenGoal,
        onOpenWeight = viewModel::onOpenWeight,
        onOpenLabelScan = viewModel::onOpenLabelScan,
        onOpenLabelDebug = viewModel::onOpenLabelDebug,
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

        Overlay.Scan -> ScannerDialog(
            onScanned = viewModel::onScanned,
            onDismiss = viewModel::onDismissOverlay,
        )

        is Overlay.NewProduct -> NewProductDialog(
            gtin = overlay.gtin,
            draft = overlay.draft,
            numbers = overlay.numbers,
            names = overlay.names,
            onConfirm = { name, nutriments, servingG ->
                viewModel.onSaveNewProduct(overlay.gtin, name, nutriments, servingG)
            },
            onScanLabel = { current -> viewModel.onScanLabel(overlay.gtin, current) },
            onDismiss = viewModel::onDismissOverlay,
        )

        is Overlay.LabelScan -> LabelScannerDialog(
            onRead = { reading -> viewModel.onLabelRead(overlay.gtin, overlay.current, reading) },
            // Закрыть съёмку — вернуться в форму, а не потерять её вместе
            // с набранным именем и введённым кодом.
            onDismiss = { viewModel.onLabelCancelled(overlay.gtin, overlay.current) },
        )

        Overlay.LabelDebug -> LabelScannerDialog(
            onRead = viewModel::onLabelDebugRead,
            onDismiss = viewModel::onDismissOverlay,
            debug = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    state: DiaryUiState,
    onInputChange: (String) -> Unit,
    onPick: (ResolvedItem) -> Unit,
    onPickScanned: (ResolvedItem) -> Unit,
    onOpenScan: () -> Unit,
    onPickCandidate: (FoodCandidate) -> Unit,
    onPickForMeal: (FoodCandidate, MealType) -> Unit,
    onPickPlan: (PlanOption) -> Unit,
    onDismissGap: (MealType) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onEditEntry: (DiaryEntryEntity) -> Unit,
    onOpenGoal: () -> Unit,
    onOpenWeight: () -> Unit,
    onOpenLabelScan: () -> Unit,
    onOpenLabelDebug: () -> Unit,
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
                    // Отладочная точка входа в распознавание этикетки. Стоит здесь,
                    // а не за промахом штрих-кода, ровно потому, что проверять разбор
                    // приходится на пачках, которые в базе как раз есть.
                    IconButton(onClick = onOpenLabelDebug) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = "Проверить распознавание этикетки",
                        )
                    }
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
            state.personal.mealGap?.let { gap ->
                GapBanner(
                    gap = gap,
                    onPick = { candidate -> onPickForMeal(candidate, gap.meal) },
                    onDismiss = { onDismissGap(gap.meal) },
                )
            }

            // Шапка и график уезжают вместе со списком, а не висят закреплёнными:
            // триста точек несдвигаемой высоты на небольшом экране не оставили бы
            // места самим записям. Закреплена только строка ввода.
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item { GoalCard(state.totals, state.goal) }

                item {
                    WeekChart(
                        days = state.week,
                        goalKcal = state.goal?.kcal,
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                    )
                }

                if (state.entries.isEmpty()) {
                    item { EmptyHint() }
                } else {
                    mealSections(
                        entries = state.entries,
                        onEditEntry = onEditEntry,
                        onDeleteEntry = onDeleteEntry,
                    )
                }

                state.personal.dayPlan?.let { plan ->
                    item { RemainingCard(plan, onPickPlan) }
                }
            }

            InputArea(
                state = state,
                onInputChange = onInputChange,
                onPick = onPick,
                onPickScanned = onPickScanned,
                onOpenScan = onOpenScan,
                onOpenLabelScan = onOpenLabelScan,
                onPickCandidate = onPickCandidate,
            )
        }
    }
}

/**
 * Цель и остаток до неё.
 *
 * Крупным числом стоит остаток, а не съеденное, и это главная перемена на экране:
 * трекер открывают с вопросом «сколько мне ещё можно», а не «сколько я уже съел».
 * Второе выводится из первого, обратное — нет.
 *
 * Крупное число на экране ровно одно: соревнование двух заголовков за внимание
 * означает, что не читается ни один.
 */
@Composable
private fun GoalCard(totals: NutrimentTotals, goal: DailyGoalEntity?) {
    val target = goal?.kcal?.takeIf { it > 0 }
    val remaining = target?.let { it - totals.kcal }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            when {
                remaining == null -> "Съедено за день"
                remaining >= 0 -> "Осталось"
                else -> "Перебор"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            (remaining?.let { abs(it) } ?: totals.kcal).toString(),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            // Перебор выделяется цветом, но остаётся числом, а не упрёком:
            // подпись выше уже сказала, что произошло.
            color = if (remaining != null && remaining < 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )

        Text(
            if (target == null) {
                "ккал · задайте цель, чтобы видеть остаток"
            } else {
                "ккал · съедено ${totals.kcal} из $target"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (target != null) {
            LinearProgressIndicator(
                progress = { (totals.kcal.toFloat() / target).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Macro("Б", totals.protCg)
            Macro("Ж", totals.fatCg)
            Macro("У", totals.carbCg)
        }
    }
}

@Composable
private fun Macro(label: String, centigrams: Int) {
    Column {
        Text(formatCentigrams(centigrams), style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Записи дня, разложенные по приёмам пищи.
 *
 * `MealType` проставляется каждой записи по времени добавления и до сих пор жил
 * только внутри моделей персонализации. Здесь он наконец виден: день, разбитый
 * на завтрак-обед-ужин, читается с одного взгляда, а плоский список — нет.
 *
 * Пустые приёмы не показываются. Пустая секция «Ужин» в три часа дня — это не
 * структура, а упрёк; про действительно пропущенный приём отдельно спрашивает
 * [GapBanner], и делает это по данным, а не по часам.
 */
private fun LazyListScope.mealSections(
    entries: List<DiaryEntryEntity>,
    onEditEntry: (DiaryEntryEntity) -> Unit,
    onDeleteEntry: (Long) -> Unit,
) {
    val byMeal = entries.groupBy { it.meal }

    MEAL_ORDER.forEach { meal ->
        val mealEntries = byMeal[meal].orEmpty()
        if (mealEntries.isEmpty()) return@forEach

        item(key = "meal-$meal") {
            MealHeader(meal, mealEntries.sumOf { it.totals().kcal })
        }
        items(mealEntries, key = { it.id }) { entry ->
            EntryRow(
                entry = entry,
                onEdit = { onEditEntry(entry) },
                onDelete = { onDeleteEntry(entry.id) },
            )
        }
    }
}

@Composable
private fun MealHeader(meal: MealType, kcal: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            mealName(meal),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$kcal ккал",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Порядок секций — по ходу дня, а не по алфавиту и не по тому, что записали раньше. */
private val MEAL_ORDER = listOf(
    MealType.BREAKFAST,
    MealType.LUNCH,
    MealType.DINNER,
    MealType.SNACK,
)

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
    onPickScanned: (ResolvedItem) -> Unit,
    onOpenScan: () -> Unit,
    onOpenLabelScan: () -> Unit,
    onPickCandidate: (FoodCandidate) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
            if (state.searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (state.scanned != null) {
                ScannedRow(state.scanned, onPickScanned)
                HorizontalDivider()
            } else if (state.suggestions.isNotEmpty()) {
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

            // Съёмка этикетки — самостоятельный способ добавить еду, а не запасной
            // выход из промаха штрих-кода. У развесного, домашнего и вскрытой пачки
            // кода нет вовсе, а таблица пищевой ценности есть, и вести к ней через
            // сканирование несуществующего кода незачем.
            //
            // Кнопка полноширинная и подписанная, а не иконка рядом со штрих-кодом:
            // это разные вещи. Штрих-код опознаёт товар целиком, этикетка заводит
            // новый — и цена ошибки у них разная.
            OutlinedButton(
                onClick = onOpenLabelScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Снять этикетку")
            }

            // Кнопки отправки нет намеренно: добавляет только тап по чипсу.
            // Вторая точка входа означала бы выбор «первого попавшегося» вслепую —
            // ровно того, от чего предложения и защищают.
            //
            // Кнопка справа не отправляет, а переключает способ ввода: у товара
            // в упаковке штрих-код точнее любого названия, и заставлять человека
            // набирать «активиа натуральная 4%» вместо одного наведения камеры незачем.
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                placeholder = { Text("Что съели?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = onOpenScan) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Сканировать штрих-код")
                    }
                },
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
 * Товар, опознанный по штрих-коду.
 *
 * Тот же чипс, что и у текстовой выдачи, и то же правило: подтверждение остаётся
 * за человеком. Скан знает, что это за товар, но не знает, сколько его съели, —
 * и молча записать целую пачку было бы хуже, чем не записать ничего.
 */
@Composable
private fun ScannedRow(item: ResolvedItem, onPick: (ResolvedItem) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            if (item.gramsGuessed) "Найдено по коду · вес поправьте после добавления" else "Найдено по коду",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        SuggestionChip(item) { onPick(item) }
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
