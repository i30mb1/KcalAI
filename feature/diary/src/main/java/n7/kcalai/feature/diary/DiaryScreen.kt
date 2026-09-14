package n7.kcalai.feature.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.feature.scanner.LabelDebugDialog
import n7.kcalai.feature.scanner.LabelScannerDialog
import n7.kcalai.feature.scanner.ScannerDialog
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.MealType
import n7.kcalai.personal.PlanOption
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.ui.KcalTheme

@Composable
fun DiaryRoute(viewModel: DiaryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DiaryScreen(
        state = state,
        onInputChange = viewModel::onInputChange,
        onPick = { item -> viewModel.onPick(item) },
        onPickScanned = viewModel::onPickScanned,
        onPickCandidate = { candidate -> viewModel.onPickPrediction(candidate) },
        onPickForMeal = viewModel::onPickPrediction,
        onPickPlan = { option -> option.items.forEach { viewModel.onPickPrediction(it) } },
        onDismissGap = viewModel::onDismissMealGap,
        onEditEntry = viewModel::onEditEntry,
        onLogWeight = viewModel::onLogWeight,
        onOpenGoal = viewModel::onOpenGoal,
        onOpenScan = viewModel::onOpenScan,
        onOpenLabelScan = viewModel::onOpenLabelScan,
        onOpenLabelDebug = viewModel::onOpenLabelDebug,
    )

    when (val overlay = state.overlay) {
        Overlay.None -> Unit

        is Overlay.Goal -> GoalSheet(
            goal = state.goal,
            tdee = state.tdee,
            date = state.date,
            focus = overlay.field,
            onConfirm = viewModel::onSetGoal,
            onDismiss = viewModel::onDismissOverlay,
        )

        is Overlay.EditEntry -> EntrySheet(
            entry = overlay.entry,
            onSave = { grams, meal -> viewModel.onSaveEntry(overlay.entry, grams, meal) },
            onDelete = { viewModel.onDeleteEntry(overlay.entry.id) },
            onDismiss = viewModel::onDismissOverlay,
        )

        Overlay.Scan -> ScannerDialog(
            onScanned = viewModel::onScanned,
            onDismiss = viewModel::onDismissOverlay,
        )

        is Overlay.LabelScan -> LabelScannerDialog(
            onSave = viewModel::onSaveNewProduct,
            onDismiss = viewModel::onDismissOverlay,
            // Код с промаха скана: сам экран его тоже ловит, но отсканированный
            // человеком намеренно сильнее случайно попавшего в кадр.
            gtin = overlay.gtin,
        )

        Overlay.LabelDebug -> LabelDebugDialog(
            onRead = viewModel::onLabelDebugRead,
            onDismiss = viewModel::onDismissOverlay,
        )
    }
}

/**
 * Главный экран: лента-диалог.
 *
 * Три зоны, и только средняя прокручивается. Шапка сверху отвечает на «сколько
 * мне ещё можно», лента посередине — «что у меня сегодня было», композер внизу
 * добавляет еду. Прошлый вариант складывал всё это в один скроллящийся список,
 * и остаток до цели уезжал за край ровно тогда, когда человек смотрел на записи.
 */
@Composable
fun DiaryScreen(
    state: DiaryUiState,
    onInputChange: (String) -> Unit,
    onPick: (ResolvedItem) -> Unit,
    onPickScanned: (ResolvedItem) -> Unit,
    onPickCandidate: (FoodCandidate) -> Unit,
    onPickForMeal: (FoodCandidate, MealType) -> Unit,
    onPickPlan: (PlanOption) -> Unit,
    onDismissGap: (MealType) -> Unit,
    onEditEntry: (DiaryEntryEntity) -> Unit,
    onLogWeight: () -> Unit,
    onOpenGoal: (GoalField) -> Unit,
    onOpenScan: () -> Unit,
    onOpenLabelScan: () -> Unit,
    onOpenLabelDebug: () -> Unit,
) {
    val listState = rememberLazyListState()

    // Шапка сжимается ровно тогда, когда лента сдвинулась с начала: любой порог
    // в точках означал бы состояние «уже скроллю, а шапка ещё целая».
    val collapsed by remember { derivedStateOf { listState.canScrollBackward } }

    Column(Modifier.fillMaxSize().background(KcalTheme.colors.bg)) {
        Header(
            date = state.date,
            totals = state.totals,
            goal = state.goal,
            week = state.week,
            collapsed = collapsed,
            onOpenGoal = onOpenGoal,
        )

        Feed(
            feed = state.feed,
            listState = listState,
            onEditEntry = onEditEntry,
            onPickForMeal = onPickForMeal,
            onDismissGap = onDismissGap,
            onPickPlan = onPickPlan,
            modifier = Modifier.weight(1f),
        )

        Composer(
            input = state.input,
            row = state.composer,
            onInputChange = onInputChange,
            onPick = onPick,
            onPickScanned = onPickScanned,
            onPickCandidate = onPickCandidate,
            onLogWeight = onLogWeight,
            onOpenScan = onOpenScan,
            onOpenLabelScan = onOpenLabelScan,
            onOpenLabelDebug = onOpenLabelDebug,
        )
    }
}

/**
 * Лента реплик.
 *
 * Вся логика «что и в каком порядке» осталась в [DiaryViewModel]: здесь только
 * соответствие «тип реплики — пузырёк». Поэтому новая модель персонализации
 * добавляется одной строкой в этом `when`, а не очередным баннером в шапке.
 */
@Composable
private fun Feed(
    feed: List<FeedItem>,
    listState: LazyListState,
    onEditEntry: (DiaryEntryEntity) -> Unit,
    onPickForMeal: (FoodCandidate, MealType) -> Unit,
    onDismissGap: (MealType) -> Unit,
    onPickPlan: (PlanOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Новая реплика всегда внизу — как в любом диалоге. Без этого добавленный
    // продукт оказывается за нижним краем, и человек не видит, что его записали.
    LaunchedEffect(feed.size) {
        if (feed.isNotEmpty()) listState.animateScrollToItem(feed.lastIndex)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        items(feed.size, key = { index -> feed[index].key }) { index ->
            val item = feed[index]

            // Обёртка нужна ради animateItem: пузырьки о своём месте в списке
            // не знают и знать не должны.
            //
            // Исчезновение не анимируется намеренно. Уходящий элемент остаётся
            // скомпонованным на время затухания и рисует СТАРЫЕ данные; если он
            // при этом оказался за нижним краем — а «ничего не нашлось» стоит
            // ровно там, — анимация не доигрывается, и реплика с прошлым запросом
            // висит на экране. Пропадать реплика обязана сразу.
            Box(Modifier.animateItem(fadeOutSpec = null)) {
                when (item) {
                    FeedItem.DaySeparator -> DaySeparator()

                    is FeedItem.Meal -> MealBubble(item, onEditEntry)

                    is FeedItem.Summary -> SummaryBubble(item)

                    is FeedItem.Gap -> GapBubble(
                        item = item,
                        onPick = { candidate -> onPickForMeal(candidate, item.gap.meal) },
                        onDismiss = { onDismissGap(item.gap.meal) },
                    )

                    is FeedItem.Remaining -> RemainingBubble(item, onPickPlan)

                    is FeedItem.Over -> OverBubble(item)

                    is FeedItem.NotFound -> NotFoundBubble(item)

                    is FeedItem.WeightLogged -> WeightBubble(item)
                }
            }
        }
    }
}
