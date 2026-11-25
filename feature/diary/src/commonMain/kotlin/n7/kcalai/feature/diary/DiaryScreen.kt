package n7.kcalai.feature.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.mascot.MascotEvent
import n7.kcalai.mascot.MascotQuestion
import n7.kcalai.feature.scanner.LabelDebugDialog
import n7.kcalai.feature.scanner.ScanStep
import n7.kcalai.feature.scanner.ScannerDialog
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.MealType
import n7.kcalai.personal.PlanOption
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.ui.Crumbling
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.popIn

/**
 * @param labelDebug долгое нажатие на камеру открывает отладку разбора этикетки,
 *        а над камерой стоит счётчик кадров. Только в отладочной сборке: человеку
 *        из магазина приложений экран с моноширинными строками разбора, кнопка
 *        «В лог» и «кадр 48 · 542 мс» ни к чему, а долгое нажатие на кнопку —
 *        обычный способ узнать, что она делает.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DiaryRoute(viewModel: DiaryViewModel, labelDebug: Boolean = false) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Экран мог пролежать в фоне через полночь — дата пересматривается на каждом возврате.
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    // Из прошлого дня «назад» ведёт к сегодня, а не прочь из приложения:
    // открыть вчера и выйти одним жестом — не то, чего человек ждёт.
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.pastMeal != null,
        onBackCompleted = { viewModel.onSelectDay(state.today.toEpochDays()) },
    )

    DiaryScreen(
        state = state,
        onSelectDay = viewModel::onSelectDay,
        onPickMeal = viewModel::onPickMeal,
        onInputChange = viewModel::onInputChange,
        onPick = { item -> viewModel.onPick(item) },
        onPickScanned = viewModel::onPickScanned,
        onPickCandidate = { candidate -> viewModel.onPickPrediction(candidate) },
        onPickGap = viewModel::onPickGap,
        onPickPlan = { option -> option.items.forEach { viewModel.onPickPrediction(it) } },
        onDismissGap = viewModel::onDismissMealGap,
        onSendOutbox = viewModel::onSendOutbox,
        onDeclineOutbox = viewModel::onDeclineOutbox,
        onAskAssistant = viewModel::onAskAssistant,
        onAddProduct = viewModel::onAddProduct,
        onEditEntry = viewModel::onEditEntry,
        onUndoDelete = viewModel::onUndoDelete,
        onLogWeight = viewModel::onLogWeight,
        onSubmit = viewModel::onSubmit,
        onOpenGoal = viewModel::onOpenGoal,
        onFiberTap = viewModel::onFiberTapped,
        onOpenScan = viewModel::onOpenScan,
        onOpenLabelDebug = if (labelDebug) viewModel::onOpenLabelDebug else null,
        onMascotEvent = viewModel::onMascotEvent,
        onAnswerAsk = { question, yes ->
            when (question) {
                MascotQuestion.NAME -> if (yes) viewModel.onAskName() else viewModel.onAnswerName(null)
                MascotQuestion.WRITE -> viewModel.onAnswerWrite(yes)
                MascotQuestion.TREADMILL -> viewModel.onAnswerTreadmill(yes)
                MascotQuestion.STEPS -> viewModel.onAnswerSteps(yes)
            }
        },
        onAnswerOffer = viewModel::onAnswerOffer,
        onAnswerBrowser = viewModel::onAnswerBrowser,
        onSendToPeer = viewModel::onSendToPeer,
        onHideMessage = viewModel::onHideMessage,
    )

    // Пущенный браузер на связи — экрану гаснуть нельзя: страница живёт, пока открыт дневник.
    val screen = LocalScreenAwake.current
    DisposableEffect(state.keepScreenOn) {
        screen.keep(state.keepScreenOn)
        onDispose { screen.keep(false) }
    }

    when (val overlay = state.overlay) {
        Overlay.None -> Unit

        // Цель правится всегда с сегодня, даже если открыт прошлый день:
        // переписать задним числом прошлую цель нельзя по замыслу.
        is Overlay.Goal -> GoalSheet(
            goal = state.todayGoal,
            tdee = state.tdee,
            lastWeightGrams = state.lastWeightGrams,
            onConfirm = viewModel::onSetGoal,
            onDismiss = viewModel::onDismissOverlay,
            onSex = viewModel::onSex,
        )

        is Overlay.EditEntry -> EntrySheet(
            entry = overlay.entry,
            onSave = { grams, meal -> viewModel.onSaveEntry(overlay.entry, grams, meal) },
            onSaveProduct = { name, per100 -> viewModel.onCorrectProduct(overlay.entry, name, per100) },
            onDelete = { viewModel.onDeleteEntry(overlay.entry) },
            onDismiss = viewModel::onDismissOverlay,
        )

        // Одно окно на все шаги — от кода до карточки: оно не закрывается между
        // ними, и камера не перезапускается. Шаг меняет экранная модель.
        is Overlay.Scan -> ScannerDialog(
            step = overlay.step,
            onScanned = viewModel::onScanned,
            onNoBarcode = viewModel::onNoBarcode,
            onSave = viewModel::onSaveNewProduct,
            findByName = viewModel::matchLabelName,
            onPickMatch = viewModel::onPickLabelMatch,
            onDismiss = viewModel::onDismissOverlay,
            // Счётчик кадров — инструмент разработчика, как и отладка разбора.
            frameStats = labelDebug,
        )

        // Свой продукт из ненайденного слова. Камера — только ради штрих-кода и только
        // по кнопке: пойманный код встаёт в форму, «Нет штрих-кода» возвращает к ней.
        is Overlay.AddProduct -> if (overlay.scanning) {
            ScannerDialog(
                step = ScanStep.Barcode,
                onScanned = viewModel::onProductCode,
                onNoBarcode = viewModel::onCancelProductCode,
                onSave = { _, _, _ -> },
                findByName = { null },
                onPickMatch = {},
                onDismiss = viewModel::onCancelProductCode,
            )
        } else {
            NewProductSheet(
                form = overlay.form,
                onChange = viewModel::onProductForm,
                onScanCode = viewModel::onScanProductCode,
                onSave = viewModel::onSaveAddedProduct,
                onDismiss = viewModel::onDismissOverlay,
            )
        }

        Overlay.LabelDebug -> LabelDebugDialog(
            onRead = viewModel::onLabelDebugRead,
            onDismiss = viewModel::onDismissOverlay,
        )

        Overlay.Name -> NameSheet(
            onConfirm = viewModel::onAnswerName,
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
    onSelectDay: (Long) -> Unit,
    onPickMeal: (MealType) -> Unit,
    onInputChange: (String) -> Unit,
    onPick: (ResolvedItem) -> Unit,
    onPickScanned: (ResolvedItem) -> Unit,
    onPickCandidate: (FoodCandidate) -> Unit,
    /** Ответ на «Обед был?»: еда ложится в тот приём, о котором спрашивали. */
    onPickGap: (FeedItem.Gap, FoodCandidate) -> Unit,
    onPickPlan: (PlanOption) -> Unit,
    onDismissGap: (MealType) -> Unit,
    onSendOutbox: () -> Unit,
    onDeclineOutbox: () -> Unit,
    /** «Разобрать» в реплике «не нашлось»: фразу разбирает нейросеть. */
    onAskAssistant: (String) -> Unit,
    /** «Добавить» в реплике «не нашлось»: форма своего продукта с этим названием. */
    onAddProduct: (String) -> Unit,
    onEditEntry: (DiaryEntryEntity) -> Unit,
    onUndoDelete: (FeedItem.Deleted) -> Unit,
    onLogWeight: () -> Unit,
    onSubmit: () -> Boolean,
    onOpenGoal: () -> Unit,
    /** Тап по клетчатке в шапке: о ней рассказывает Зёрнышко. */
    onFiberTap: () -> Unit,
    onOpenScan: () -> Unit,
    onOpenLabelDebug: (() -> Unit)?,
    /** Ответ на вопрос персонажа: `true` — «Сказать» / «Пишите», `false` — «Не скажу» / «Не надо». */
    onAnswerAsk: (MascotQuestion, Boolean) -> Unit,
    onAnswerOffer: (Long, Boolean) -> Unit,
    onAnswerBrowser: (Long, Boolean) -> Unit,
    onSendToPeer: (String) -> Unit,
    /** Корзинка у сообщения персонажа. */
    onHideMessage: (Long) -> Unit = {},
    /** С персонажем что-то сделали: ткнули, взяли в руку, погладили, разбудили. */
    onMascotEvent: (MascotEvent) -> Unit,
) {
    // База ещё не ответила. Шапка с нулями и сводка «цель не задана» были бы
    // неправдой, а через долю секунды их сменили бы настоящие цифры — и записи
    // дня выпрыгнули бы с анимацией, положенной только новым.
    if (!state.loaded) {
        Box(Modifier.fillMaxSize().background(KcalTheme.colors.bg))
        return
    }

    // У каждого дня своя переписка — и своя прокрутка: вчерашняя лента
    // открывается у своего конца, а не там, где остановилась сегодняшняя.
    val listState = key(state.date) { rememberLazyListState() }

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

    // Высота шапки в обоих видах: раскрываясь, она отнимает у ленты разницу.
    // Пока идёт анимация, высота промежуточная, крайние значения — два вида.
    var openHeight by remember { mutableIntStateOf(0) }
    var closedHeight by remember { mutableIntStateOf(0) }

    // Клавиатура сворачивает шапку сразу, не дожидаясь, пока лента сдвинется:
    // человек сел писать, и треть экрана под сводкой ему сейчас нужна меньше
    // всего. Раньше шапка уходила только когда набранное вытесняло ленту
    // за край — то есть от случая к случаю.
    //
    // Ушла клавиатура — шапка возвращается, если лента и под ней помещается
    // целиком. Раньше она оставалась свёрнутой до жеста «потянуть вниз»,
    // о котором никто не знает: утром, с одной репликой в ленте, человек
    // видел пустой экран без плиток и недели. Петли, от которой предостерегает
    // комментарий выше, тут нет: раскрытие — разовый ответ на уход клавиатуры,
    // и только когда места хватает с запасом.
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(ime, density) {
        snapshotFlow { ime.getBottom(density) > 0 }
            .collect { shown ->
                if (shown) {
                    collapsed = true
                } else if (collapsed) {
                    // Кадр, чтобы лента приняла высоту без клавиатуры.
                    withFrameNanos { }
                    val growth = (openHeight - closedHeight).coerceAtLeast(0)
                    if (headerFits(listState.spareHeight(), growth)) collapsed = false
                }
            }
    }
    /*
     * Тем же жестом уходит и клавиатура: раскрыть шапку человек тянется, чтобы
     * посмотреть на остаток и макросы, а не чтобы продолжить печатать. Оставить
     * клавиатуру значило бы отдать ей половину экрана ровно под тем, на что
     * человек сейчас смотрит, и заставить убирать её вторым жестом.
     *
     * Снимается фокус, а не просто скрывается IME: поле осталось бы активным,
     * и повторный тап по нему клавиатуру уже не вернул бы — фокус на месте,
     * событию взяться негде.
     */
    val focus = LocalFocusManager.current
    val expandOnPull = remember(focus) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Не потраченная лентой прокрутка вниз — значит, она уже наверху.
                // Условие на `collapsed` держит это одним срабатыванием на жест:
                // непотраченного хватает на каждый кадр, а фокус снимать нужно раз.
                if (available.y > 0f && collapsed) {
                    collapsed = false
                    focus.clearFocus()
                }
                return Offset.Zero
            }
        }
    }

    // Персонажи — один слой поверх всего экрана: плитки, реплики и чипс только
    // сообщают, где у них место, а кто где стоит, решает [mascotPlaces].
    val anchors = remember { MascotAnchors() }
    // Расстановка через derivedStateOf: прокрутка двигает места каждый кадр, а кто
    // где стоит, меняется редко — экран перекомпонуется только тогда.
    val feed by rememberUpdatedState(state.feed)
    // Подсказки «обычно» — только пока строка ввода в фокусе; персонаж стоит на том ряде, что виден.
    var composerFocused by remember { mutableStateOf(false) }
    val row = visibleRow(state.composer, composerFocused)
    val owner by rememberUpdatedState(chipOwner(row))
    // Шиты со своими персонажами: на главном экране их на это время нет.
    val offstage by rememberUpdatedState(state.overlay is Overlay.Goal || state.overlay is Overlay.AddProduct)
    val places by remember(anchors) {
        derivedStateOf {
            mascotPlaces(feed, shownAnchors(anchors.rects, anchors.coverBottom, anchors.floorTop), owner, offstage)
        }
    }
    // Палец на экране — для взглядов персонажей; касаний наблюдатель не забирает.
    val pointer = remember { StagePointer() }
    Box(Modifier.fillMaxSize().observeTouches(pointer)) {
    CompositionLocalProvider(LocalMascotAnchors provides anchors) {
    // Высоты шапки и строки ввода — отступы ленты: лента идёт под обеими,
    // и в покое реплики стоят там же, где стояли между непрозрачными полосами.
    var headerHeight by remember { mutableIntStateOf(0) }
    var composerHeight by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(KcalTheme.colors.bg)) {
        // Новый день — новая лента целиком: его записи не «приходят» в переписку,
        // они в ней были, и выпрыгивать с анимацией им незачем.
        key(state.date) {
        Feed(
            feed = state.feed,
            listState = listState,
            topInset = with(density) { headerHeight.toDp() },
            bottomInset = with(density) { composerHeight.toDp() },
            onEditEntry = onEditEntry,
            onUndoDelete = onUndoDelete,
            onPickGap = onPickGap,
            onDismissGap = onDismissGap,
            onSendOutbox = onSendOutbox,
            onDeclineOutbox = onDeclineOutbox,
            onAskAssistant = onAskAssistant,
            onAddProduct = onAddProduct,
            onPickPlan = onPickPlan,
            onOpenGoal = onOpenGoal,
            onAnswerAsk = onAnswerAsk,
            onAnswerOffer = onAnswerOffer,
            onAnswerBrowser = onAnswerBrowser,
            onSendToPeer = onSendToPeer,
            onHideMessage = onHideMessage,
            modifier = Modifier.fillMaxSize().nestedScroll(expandOnPull),
        )
        }

        Header(
            date = state.date,
            today = state.today,
            onSelectDay = onSelectDay,
            totals = state.totals,
            fiber = state.fiber,
            fiberGoal = fiberGoal(state.goal),
            goal = state.goal,
            week = state.week,
            collapsed = collapsed,
            emptyHomes = emptyHomes(places),
            onOpenGoal = onOpenGoal,
            onFiberTap = onFiberTap,
            modifier = Modifier
                .onSizeChanged { size ->
                    headerHeight = size.height
                    if (collapsed) {
                        closedHeight = if (closedHeight == 0) size.height else minOf(closedHeight, size.height)
                    } else {
                        openHeight = maxOf(openHeight, size.height)
                    }
                }
                .onGloballyPositioned { anchors.coverBottom = it.boundsInRoot().bottom },
        )

        Composer(
            input = state.input,
            row = row,
            action = state.action,
            pastMeal = state.pastMeal,
            sendingTo = state.sendingTo,
            onPickMeal = onPickMeal,
            onInputChange = onInputChange,
            onPick = onPick,
            onPickScanned = onPickScanned,
            onPickCandidate = onPickCandidate,
            onLogWeight = onLogWeight,
            onSubmit = onSubmit,
            onOpenScan = onOpenScan,
            onOpenLabelDebug = onOpenLabelDebug,
            onFocusChange = { composerFocused = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { composerHeight = it.height }
                .onGloballyPositioned { anchors.floorTop = it.boundsInRoot().top },
        )
    }
    }
        MascotStage(
            places = places,
            anchors = anchors,
            pointer = pointer,
            input = state.input,
            speech = state.speech,
            cue = state.cue,
            night = state.night,
            onEvent = onMascotEvent,
        )
    }
}

/**
 * Раскрыть ли шапку, когда ушла клавиатура.
 *
 * @param spare свободное место под последней репликой, когда лента вся
 *        на экране; `null` — лента не помещается и со свёрнутой шапкой
 * @param growth на сколько шапка вырастет, раскрывшись
 */
internal fun headerFits(spare: Int?, growth: Int): Boolean = spare != null && spare >= growth

/** Свободное место под последней репликой, если лента вся на экране; иначе `null`. */
private fun LazyListState.spareHeight(): Int? {
    if (canScrollBackward || canScrollForward) return null
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return info.viewportEndOffset - info.viewportStartOffset
    return info.viewportEndOffset - info.afterContentPadding - (last.offset + last.size)
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
    /** Высота шапки над лентой: лента идёт под ней, и первая реплика начинается ниже. */
    topInset: Dp,
    /** Высота строки ввода под лентой: последняя реплика стоит над ней. */
    bottomInset: Dp,
    onEditEntry: (DiaryEntryEntity) -> Unit,
    onUndoDelete: (FeedItem.Deleted) -> Unit,
    onPickGap: (FeedItem.Gap, FoodCandidate) -> Unit,
    onDismissGap: (MealType) -> Unit,
    onSendOutbox: () -> Unit,
    onDeclineOutbox: () -> Unit,
    /** «Разобрать» в реплике «не нашлось»: фразу разбирает нейросеть. */
    onAskAssistant: (String) -> Unit,
    onAddProduct: (String) -> Unit,
    onPickPlan: (PlanOption) -> Unit,
    onOpenGoal: () -> Unit,
    onAnswerAsk: (MascotQuestion, Boolean) -> Unit,
    onAnswerOffer: (Long, Boolean) -> Unit,
    onAnswerBrowser: (Long, Boolean) -> Unit,
    onSendToPeer: (String) -> Unit,
    onHideMessage: (Long) -> Unit = {},
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
     * а с ним на столько же растёт отступ ленты снизу. Само по себе это ленту
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset + 8.dp, bottom = bottomInset + 6.dp),
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
                is FeedItem.DaySeparator -> TransformOrigin.Center
                is FeedItem.Meal, is FeedItem.Question -> TransformOrigin(1f, 1f)
                else -> TransformOrigin(0f, 1f)
            }
            // Зазор сверху — по соседу: реплики одной стороны подряд стоят тесно,
            // смена собеседника — просторнее, как в мессенджере. См. gapBefore.
            Box(
                Modifier
                    .animateItem(fadeOutSpec = null)
                    .padding(top = gapBefore(feed.getOrNull(index - 1), item))
                    .popIn(origin = origin, lift = 12.dp, enabled = fresh)
            ) {
                when (item) {
                    is FeedItem.DaySeparator -> DaySeparator(item.label)

                    is FeedItem.EmptyDay -> EmptyDayBubble(item)

                    is FeedItem.Meal -> MealBubble(item, onEditEntry)

                    is FeedItem.Summary -> SummaryBubble(item, onSetGoal = onOpenGoal)

                    is FeedItem.Gap -> GapBubble(
                        item = item,
                        onPick = { candidate -> onPickGap(item, candidate) },
                        onDismiss = { onDismissGap(item.gap.meal) },
                    )

                    is FeedItem.Remaining -> RemainingBubble(item, onPickPlan)

                    is FeedItem.Over -> OverBubble(item)

                    is FeedItem.NotFound -> NotFoundBubble(
                        item = item,
                        onAsk = { onAskAssistant(item.query) },
                        onAdd = { onAddProduct(item.query) },
                    )

                    is FeedItem.WeightLogged -> WeightBubble(item)
                    is FeedItem.Reply -> ReplyBubble(item)
                    is FeedItem.Question -> QuestionBubble(item)
                    is FeedItem.Answer -> AnswerBubble(item)
                    is FeedItem.Deleted -> DeletedBubble(item, onUndo = { onUndoDelete(item) })
                    is FeedItem.Outbox -> OutboxBubble(item, onSendOutbox, onDeclineOutbox)
                    // Корзинка рассыпает сообщение на крошки, и только потом оно уходит из переписки.
                    is FeedItem.Said -> Crumbling(onHide = { onHideMessage(item.id) }) { hide -> SaidBubble(item, onHide = hide) }
                    // Отвеченный вопрос — уже просто переписка: его можно убрать; ждущий ответа — нет.
                    is FeedItem.Ask -> Crumbling(onHide = { onHideMessage(item.id) }.takeIf { item.choice != null }) { hide ->
                        AskBubble(
                            item = item,
                            onYes = { onAnswerAsk(item.question, true) },
                            onNo = { onAnswerAsk(item.question, false) },
                            onHide = hide,
                        )
                    }
                    is FeedItem.Nearby -> NearbyBubble(item, onSendToPeer)
                    is FeedItem.Offer -> OfferBubble(
                        item = item,
                        onYes = { onAnswerOffer(item.id, true) },
                        onNo = { onAnswerOffer(item.id, false) },
                    )
                    is FeedItem.Browser -> BrowserBubble(
                        item = item,
                        onYes = { onAnswerBrowser(item.id, true) },
                        onNo = { onAnswerBrowser(item.id, false) },
                    )
                }
            }
        }
    }
}
