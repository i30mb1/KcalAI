package n7.kcalai.feature.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import n7.kcalai.ui.popIn

@Composable
fun DiaryRoute(viewModel: DiaryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Экран мог пролежать в фоне через полночь — дата пересматривается на каждом возврате.
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    DiaryScreen(
        state = state,
        onInputChange = viewModel::onInputChange,
        onPick = { item -> viewModel.onPick(item) },
        onPickScanned = viewModel::onPickScanned,
        onPickCandidate = { candidate -> viewModel.onPickPrediction(candidate) },
        onPickForMeal = viewModel::onPickPrediction,
        onPickPlan = { option -> option.items.forEach { viewModel.onPickPrediction(it) } },
        onDismissGap = viewModel::onDismissMealGap,
        onSendOutbox = viewModel::onSendOutbox,
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
    onSendOutbox: () -> Unit,
    onEditEntry: (DiaryEntryEntity) -> Unit,
    onLogWeight: () -> Unit,
    onOpenGoal: (GoalField) -> Unit,
    onOpenScan: () -> Unit,
    onOpenLabelScan: () -> Unit,
    onOpenLabelDebug: () -> Unit,
) {
    val listState = rememberLazyListState()

    /*
     * Шапка сжимается ровно тогда, когда лента сдвинулась с начала: любой порог
     * в точках означал бы состояние «уже скроллю, а шапка ещё целая».
     *
     * А вот раскрывается она не по положению ленты, а по жесту — тянуть вниз,
     * когда выше уже ничего нет. Вывести оба состояния из одного
     * `canScrollBackward` нельзя: высота шапки сама меняет высоту ленты. Стоило
     * реплике не влезть на пару строк, как лента сдвигалась, шапка сжималась,
     * лента получала её место и целиком помещалась — смещение сбрасывалось
     * в ноль, шапка раскрывалась, реплика снова не влезала. Экран прыгал,
     * пока анимации гонялись друг за другом. Жест человека в эту петлю
     * не входит: высота шапки на него не влияет.
     */
    var collapsed by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollBackward }
            .filter { it }
            .collect { collapsed = true }
    }

    // Клавиатура сворачивает шапку сразу, не дожидаясь, пока лента сдвинется:
    // человек сел писать, и треть экрана под сводкой ему сейчас нужна меньше
    // всего. Раньше шапка уходила только когда набранное вытесняло ленту
    // за край — то есть от случая к случаю.
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(ime, density) {
        snapshotFlow { ime.getBottom(density) > 0 }
            .filter { it }
            .collect { collapsed = true }
    }
    val expandOnPull = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Не потраченная лентой прокрутка вниз — значит, она уже наверху.
                if (available.y > 0f) collapsed = false
                return Offset.Zero
            }
        }
    }

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
            onSendOutbox = onSendOutbox,
            onPickPlan = onPickPlan,
            modifier = Modifier.weight(1f).nestedScroll(expandOnPull),
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
    onSendOutbox: () -> Unit,
    onPickPlan: (PlanOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    /*
     * Лента прилипает к низу — но только пока человек сам не отмотал её вверх.
     *
     * Раньше низ навязывался на каждое изменение размера ленты, и это ломалось
     * ровно там, где человек читает записи. Реплика «ничего не нашлось» входит
     * в ленту и уходит из неё по мере набора текста, так что на каждой второй
     * букве лента дёргалась вниз, обрывая начатую прокрутку. Со стороны это
     * и выглядит как «подлагивает и возвращает обратно»: тянешь вверх, а список
     * отматывается назад, потому что в этот момент пришло новое состояние.
     *
     * Прокрутка человека сильнее: отмотал вверх — лента остаётся там, куда он её
     * поставил, сколько бы реплик ни пришло. Вернулся к низу — прилипание
     * включается обратно. Признак того и другого один: после того как жест
     * кончился, доскролливать вперёд некуда.
     */
    var stick by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            // Первое значение — не жест человека, а состояние покоя при запуске.
            .drop(1)
            .filter { scrolling -> !scrolling }
            .collect { stick = !listState.canScrollForward }
    }

    // Новая реплика всегда внизу — как в любом диалоге. Без этого добавленный
    // продукт оказывается за нижним краем, и человек не видит, что его записали.
    //
    // Первый заход — без анимации: прокручивать ленту дня на глазах у человека,
    // который только что открыл экран, незачем, он этих записей ещё не видел.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(feed.size, feed.lastOrNull()?.key) {
        if (feed.isEmpty() || !stick) return@LaunchedEffect
        if (landed) {
            listState.animateScrollToItem(feed.lastIndex)
        } else {
            listState.scrollToItem(feed.lastIndex)
            landed = true
        }
    }

    /*
     * Клавиатура укорачивает ленту, а не накрывает её.
     *
     * Композер несёт `imePadding()`, поэтому при открытии клавиатуры он вырастает,
     * а лента под `weight(1f)` на столько же сжимается. Само по себе это ленту
     * не двигает: `LazyColumn` держится за верхний край, и всё, что было внизу,
     * уезжает под композер — последняя реплика пропадает ровно в тот момент,
     * когда человек садится писать следующую.
     *
     * Поэтому низ доводится вручную, и на каждый шаг анимации клавиатуры, а не
     * на факт её появления: она выезжает за три десятка кадров, и одного рывка
     * в начале не хватило бы — лента снова отстала бы к концу выезда. Прокрутка
     * здесь мгновенная: анимировать поверх анимации клавиатуры значит получить
     * две, живущие каждая своей жизнью.
     */
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val items by rememberUpdatedState(feed)
    LaunchedEffect(listState, ime, density) {
        snapshotFlow { ime.getBottom(density) }
            .collect {
                if (stick && items.isNotEmpty()) listState.scrollToItem(items.lastIndex)
            }
    }

    /*
     * Какие реплики появляются с пружиной: только те, что пришли в ленту после
     * первого захода. Записи дня, выскакивающие все разом при открытии, — каша.
     *
     * «Пришла в ленту» и «попала на экран» — разное: LazyColumn компонует
     * элемент заново всякий раз, когда его домотали, и реплика прыгала бы при
     * каждой прокрутке к ней. Поэтому запоминаются ключи, а не композиции:
     * ключ, который уже был в ленте, не анимируется, ушедший из ленты —
     * забывается и при возвращении сыграет снова.
     */
    val seen = remember { HashSet<String>() }
    SideEffect { seen.retainAll(feed.mapTo(HashSet()) { it.key }) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        items(feed.size, key = { index -> feed[index].key }) { index ->
            val item = feed[index]
            val fresh = remember { seen.add(item.key) && landed }

            // Обёртка нужна ради animateItem: пузырьки о своём месте в списке
            // не знают и знать не должны.
            //
            // Исчезновение не анимируется намеренно. Уходящий элемент остаётся
            // скомпонованным на время затухания и рисует СТАРЫЕ данные; если он
            // при этом оказался за нижним краем — а «ничего не нашлось» стоит
            // ровно там, — анимация не доигрывается, и реплика с прошлым запросом
            // висит на экране. Пропадать реплика обязана сразу.
            //
            // Реплика вырастает из своего «хвоста»: исходящая — справа снизу,
            // ответ — слева снизу, как в любом мессенджере.
            val origin = when (item) {
                FeedItem.DaySeparator -> TransformOrigin.Center
                is FeedItem.Meal -> TransformOrigin(1f, 1f)
                else -> TransformOrigin(0f, 1f)
            }
            Box(
                Modifier
                    .animateItem(fadeOutSpec = null)
                    .popIn(origin = origin, lift = 12.dp, enabled = fresh)
            ) {
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
                    is FeedItem.Reply -> ReplyBubble(item)
                    is FeedItem.Outbox -> OutboxBubble(item, onSendOutbox)
                }
            }
        }
    }
}
