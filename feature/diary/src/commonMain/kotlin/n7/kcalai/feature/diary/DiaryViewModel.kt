package n7.kcalai.feature.diary

import kotlinx.coroutines.flow.StateFlow
import n7.kcalai.database.stepsGoalOrDefault
import n7.kcalai.repositories.DaySteps
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import n7.kcalai.assistant.FoodAssistant
import n7.kcalai.database.Choice
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.MascotMessageEntity
import n7.kcalai.database.PeerOfferDao
import n7.kcalai.database.PeerOfferEntity
import n7.kcalai.database.WebClientEntity
import n7.kcalai.database.fiber
import n7.kcalai.database.totals
import n7.kcalai.feature.scanner.ScanStep
import n7.kcalai.mascot.MascotBrain
import n7.kcalai.mascot.MascotEvent
import n7.kcalai.mascot.Reaction
import n7.kcalai.mascot.Speech
import n7.kcalai.mascot.awaitsAnswer
import n7.kcalai.mascot.isNight
import n7.kcalai.mascot.question
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.DEFAULT_STEPS_GOAL
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FiberTotal
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.Log
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.Nutriments
import n7.kcalai.model.Sex
import n7.kcalai.model.dayLabel
import n7.kcalai.model.fiberSpeech
import n7.kcalai.model.formatDayFull
import n7.kcalai.model.formatTime
import n7.kcalai.model.parseFoodRef
import n7.kcalai.model.serialize
import n7.kcalai.personal.ConfirmedPick
import n7.kcalai.personal.DayOutline
import n7.kcalai.personal.DayPlan
import n7.kcalai.personal.MealGap
import n7.kcalai.personal.PAST_MEAL_HOURS
import n7.kcalai.personal.PersonalContext
import n7.kcalai.personal.PersonalRepository
import n7.kcalai.personal.PortionObservation
import n7.kcalai.personal.ShownCandidate
import n7.kcalai.personal.TdeeEstimate
import n7.kcalai.personal.defaultPastMeal
import n7.kcalai.personal.mealForHour
import n7.kcalai.personal.pastEntryTime
import n7.kcalai.repositories.BarcodeLookup
import n7.kcalai.repositories.DaySummary
import n7.kcalai.repositories.DayTotals
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.repositories.FoodRepository
import n7.kcalai.repositories.LabelReading
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.resolver.TextFoodResolver

/** Поле шита цели, в котором должен стоять курсор при открытии. */

/** Что сейчас поверх экрана. Шиты и диалоги взаимоисключающие, поэтому это одно состояние. */
sealed interface Overlay {
    data object None : Overlay

    /** Цель на день. [field] — куда человек ткнул: остаток или конкретная плитка. */
    data object Goal : Overlay

    /** Правка записи: вес, приём пищи, удаление. Открывается тапом по чипсу в пузырьке. */
    data class EditEntry(val entry: DiaryEntryEntity) : Overlay

    /** Камера: от штрих-кода до карточки нового продукта — одно окно, см. [ScanStep]. */
    data class Scan(val step: ScanStep = ScanStep.Barcode) : Overlay

    /**
     * Свой продукт из ненайденного слова — форма без камеры.
     *
     * @param scanning поверх формы камера читает штрих-код; форма ждёт под ней и не теряет набранное
     */
    data class AddProduct(val form: NewProductForm, val scanning: Boolean = false) : Overlay

    /**
     * Съёмка этикетки вхолостую — чтобы посмотреть, что вообще читается.
     *
     * Обычный путь к распознаванию идёт через промах штрих-кода, и проверить
     * разбор на конкретной пачке значит каждый раз найти товар, которого нет
     * в базе. Здесь то же распознавание запускается сразу и показывает себя
     * целиком: строки, маршрут разбора, что принято за ноль. В дневник отсюда
     * не попадает ничего.
     *
     * Точка входа — долгое нажатие на кнопку сканера: кнопок в шапке экран
     * больше не носит, а отладке отдельный жест дешевле отдельной кнопки.
     */
    data object LabelDebug : Overlay

    /** «Как тебя зовут?» — ответ на вопрос Яйца. */
    data object Name : Overlay

    /**
     * Цель шагов и рост — тапом по плашке шагов в ленте; открывается с тем, что уже задано.
     *
     * @param treadmill сигнал дорожки ловили: рост меряет её шаг — шит спрашивает и его
     */
    data class Steps(val goal: Int, val heightCm: Int?, val treadmill: Boolean = false) : Overlay
}

/** Что стоит над строкой ввода. Ряд ровно один — выбор между ними делает состояние. */
sealed interface ComposerRow {
    data object None : ComposerRow

    /** Набранное похоже на вес. Поиск не запускался вовсе. */
    data class Weight(val grams: Int) : ComposerRow

    /**
     * Идея 1: что человек, скорее всего, съест сейчас. Поле пустое.
     *
     * @param meal приём прошлого дня, для которого подобрано; `null` — сегодня, «сейчас»
     */
    data class Predictions(val items: List<FoodCandidate>, val meal: MealType? = null) : ComposerRow

    /** Выдача поиска по набранному. */
    data class Results(val items: List<ResolvedItem>) : ComposerRow

    /** Товар, опознанный по штрих-коду. */
    data class Scanned(val item: ResolvedItem) : ComposerRow
}

/** Всё, что посчитали модели персонализации для этого дня. */
data class PersonalState(
    /** Идея 1: что человек, скорее всего, съест сейчас. */
    val predictions: List<FoodCandidate> = emptyList(),
    /** Идея 4: приём пищи, который человек, похоже, забыл записать. */
    val mealGap: MealGap? = null,
    /** Идея 5: чем закрыть остаток дня. */
    val dayPlan: DayPlan? = null,
    /** Идея 6: расход по факту веса и съеденного. */
    val tdee: TdeeEstimate? = null,
    /** Раскладка типичного дня — для сводки в начале дня. */
    val dayOutline: DayOutline? = null,
    /** Последний записанный вес, граммы: с него начинается помощь с целью. */
    val lastWeightGrams: Int? = null,
)

data class DiaryUiState(
    val input: String = "",
    /** Готовая лента: экран её только рисует. */
    val feed: List<FeedItem> = emptyList(),
    /** Ряд чипсов над строкой ввода. */
    val composer: ComposerRow = ComposerRow.None,
    /** Чем сейчас стала кнопка у строки ввода. См. [composerAction]. */
    val action: ComposerAction = ComposerAction.SCAN,
    val totals: NutrimentTotals = NutrimentTotals.ZERO,
    /** Клетчатка открытого дня: известная часть и сколько продуктов без данных. */
    val fiber: FiberTotal = FiberTotal.ZERO,
    /** Калории по дням за неделю, последний элемент — сегодня. Всегда семь элементов. */
    val week: List<DaySummary> = emptyList(),
    /** Шаги по дням той же недели — тонкой полоской под днём; пусто — шагов нет вовсе. */
    val weekSteps: Map<Long, Int> = emptyMap(),
    /** Цель шагов — до неё растут полоски недели. */
    val stepsGoal: Int = DEFAULT_STEPS_GOAL,
    /** Открытый день: его переписка в ленте, его итоги в шапке. */
    val date: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
    /** Сегодня по календарю. Открытый день бывает прошлым, сегодня — нет. */
    val today: LocalDate = date,
    /** Цель открытого дня — та, что действовала тогда. */
    val goal: DailyGoalEntity? = null,
    /** Цель на сегодня — её правит шит: новая цель всегда действует с сегодня. */
    val todayGoal: DailyGoalEntity? = null,
    /** В какой приём пишется прошлый день. `null` — открыт сегодняшний. */
    val pastMeal: MealType? = null,
    /**
     * Имя соседа, которому сейчас пишут. `null` — пишут себе.
     *
     * Видно в подсказке строки ввода: режим, который не видно, однажды отправит
     * чужую еду в свой дневник.
     */
    val sendingTo: String? = null,
    /** Нужен шиту цели: расход по факту подставляется в калории одним тапом. */
    val tdee: TdeeEstimate? = null,
    /** Нужен шиту цели: помощь с целью начинается с уже записанного веса. */
    val lastWeightGrams: Int? = null,
    val overlay: Overlay = Overlay.None,
    /** Облачки над персонажами. */
    val speech: List<SpeechUi> = emptyList(),
    /** Последний сигнал сцене: кому подпрыгнуть, когда праздновать. */
    val cue: CueUi? = null,
    /** Ночь: персонажей застают спящими. */
    val night: Boolean = false,
    /**
     * Пущенный браузер на связи со страницей дневника: экрану гаснуть нельзя —
     * страница живёт, пока открыт дневник, и отвалилась бы посреди ввода.
     */
    val keepScreenOn: Boolean = false,
    /**
     * База ответила: день, цель и неделя прочитаны.
     *
     * До этого экран не рисует ничего — ни нулей в шапке, ни сводки «цель
     * не задана»: это не пустой день, а ещё не прочитанный.
     */
    val loaded: Boolean = false,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DiaryViewModel(
    private val diary: DiaryRepository,
    private val resolver: TextFoodResolver,
    private val personal: PersonalRepository,
    private val food: FoodRepository,
    /** Мозг персонажей: их сообщения в ленте, вопросы и ответы на них. */
    private val brain: MascotBrain,
    /**
     * Пнуть очередь отправки после того, как человек завёл продукт.
     *
     * Колбэк, а не WorkManager напрямую: планировщик — деталь сборки приложения,
     * и тащить его в модуль экрана ради одного вызова незачем.
     */
    private val onContributionQueued: () -> Unit = {},
    /** Экран съёмки этикетки закрылся — сессия записана, её можно отправлять. */
    private val onScanFinished: () -> Unit = {},
    /** Очередь отправки. `null` — сервера нет, и пузырька про него не будет. */
    private val outbox: DiaryOutbox? = null,
    /**
     * Предложения с телефона рядом. `null` — фича выключена, и предложений
     * в ленте не бывает вовсе.
     *
     * Кладёт их сюда приёмник, а не экран: сосед присылает, когда присылает,
     * и переживать закрытие экрана это должно в базе.
     */
    private val peerOffers: PeerOfferDao? = null,
    /**
     * Телефоны рядом. `null` — фича выключена: ни соседей в ленте, ни отправки.
     */
    private val peers: NearbyPeers? = null,
    /** Браузеры у страницы дневника. `null` — страницы нет, и просьб в ленте не бывает. */
    private val browsers: Browsers? = null,
    private val assistant: FoodAssistant? = null,
    /** Шаги дня — телефона и дорожки. `null` — шагов нет вовсе, и плашки в ленте не бывает. */
    private val daySteps: ((Long) -> Flow<DaySteps>)? = null,
    /** Шаги семи дней, кончая данным: пары (день, шаги). `null` — шагов нет, и полосок под неделей тоже. */
    private val weekSteps: ((Long) -> Flow<List<Pair<Long, Int>>>)? = null,
    /** Ловили ли сигнал беговой дорожки: без неё о ней не говорят, и рост не нужен. */
    private val treadmillSeen: Flow<Boolean> = flowOf(false),
    private val clock: Clock = Clock.System,
    /** Зона, в которой считаются день и час: `createdAt` хранится в UTC. */
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    private fun hourNow(): Int = clock.now().toLocalDateTime(zone).hour


    private val input = MutableStateFlow("")
    private val answer = MutableStateFlow<Answer?>(null)

    /** Строка, которую отправили раньше, чем на неё ответил поиск. */
    private var submitted: String? = null
    private val scanned = MutableStateFlow<ResolvedItem?>(null)
    private val overlay = MutableStateFlow<Overlay>(Overlay.None)
    private val personalState = MutableStateFlow(PersonalState())

    /** Что сейчас с нейросетью: есть ли она, что разбирает, что разобрать не смогла. */
    private val assist = MutableStateFlow(AssistState())

    /**
     * Реплики, которые сказало приложение в этой сессии.
     *
     * Не в базе: «Записал 82,4 кг» — это ответ на действие, а не запись дневника,
     * и возвращать его при каждом открытии экрана значило бы копить в ленте
     * отчёты о прошлых разговорах.
     */
    private val sessionReplies = MutableStateFlow<List<DayReply>>(emptyList())

    /**
     * Номер следующей реплики сессии — сквозной, а не размер списка.
     *
     * Номер по размеру списка повторил бы ключ реплики, которая уже стоит:
     * у ответов на вопрос и очереди отправки свои номера из того же счёта,
     * а `LazyColumn` на повторном ключе падает.
     */
    private var replyCount = 0

    private fun nextReplyId(): Int = replyCount++

    /** Что ждёт отправки. `null` — пусто или сервера нет; пузырька нет. */
    private val outboxItem = MutableStateFlow<FeedItem.Outbox?>(null)

    /** Облачки над персонажами — по одному на каждого. */
    private val speech = MutableStateFlow<List<SpeechUi>>(emptyList())

    /** Последний сигнал сцене. */
    private val cue = MutableStateFlow<CueUi?>(null)

    /** Сквозной номер облачков и сигналов: одинаковые подряд различаются. */
    private var stageIds = 0L

    /**
     * Сегодняшняя дата — одна на экран и на записи.
     *
     * День берётся в локальной зоне, а не из UTC-инстанта: иначе при перелёте
     * запись уезжает в соседние сутки. Состояние, а не `LocalDate.now()` на каждое
     * обращение: экран, оставленный открытым через полночь, показывал бы вчера,
     * а записывал в сегодня. Обновляется в [onResume].
     */
    private val date = MutableStateFlow(clock.todayIn(zone))
    private val today: Long get() = date.value.toEpochDays()

    /**
     * Открытый день — одна переписка. `null` — сегодня: так открытый сегодняшний
     * день сам переходит на новый, когда наступает полночь.
     */
    private val picked = MutableStateFlow<LocalDate?>(null)

    /** Приём, в который пишется прошлый день. `null` — первый незаписанный, см. [defaultPastMeal]. */
    private val pickedMeal = MutableStateFlow<MealType?>(null)

    private val shownDate: LocalDate get() = picked.value ?: date.value
    private val shownDay: Long get() = shownDate.toEpochDays()

    /** Открыт прошлый день: пишем в него задним числом, о «сейчас» не спрашиваем. */
    private val isPast: Boolean get() = shownDay < today

    /**
     * День, цель и неделя — одним потоком и без выдуманного начального значения.
     *
     * `null` — база ещё не ответила. Раньше у каждого потока было своё начальное
     * «пусто», и на холодном старте экран полсекунды показывал пустой день без
     * цели, а потом настоящие записи выпрыгивали с анимацией, будто их только
     * что добавили. Порознь потоки к тому же приходили в любом порядке: неделя
     * со столбиком за сегодня — и «0 съедено» в шапке над ней.
     */
    private val data = combine(date, picked) { now, chosen -> now to (chosen ?: now) }
        .distinctUntilChanged()
        .flatMapLatest { (now, day) ->
            // Сказанное и предложенное — одним потоком: у типизированного combine
            // потолок в пять, а дню нужно больше.
            val saidAndOffered = combine(
                // Что персонажи написали в этот день — из шторки или ответом в ленте.
                brain.messages(day.toEpochDays()),
                // Что предложили с телефона рядом; без шва предложений не бывает.
                peerOffers?.observeDay(day.toEpochDays()) ?: flowOf(emptyList()),
                // Какие браузеры просились в дневник; без страницы их не бывает.
                browsers?.requests?.observeDay(day.toEpochDays()) ?: flowOf(emptyList()),
                walkOf(day.toEpochDays()),
                weekSteps?.invoke(now.toEpochDays())?.map { it.toMap() } ?: flowOf(emptyMap()),
                ::Said,
            )
            combine(
                diary.observeDay(day.toEpochDays()),
                diary.observeGoal(day.toEpochDays()),
                // Неделя — всегда последние семь дней до сегодня: по ней и выбирают день.
                diary.observeWeek(now.toEpochDays()),
                diary.observeGoal(now.toEpochDays()),
                saidAndOffered,
            ) { dayTotals, goal, week, todayGoal, (said, offers, asked, walked, stepsByDay) ->
                DayData(day, now, dayTotals, goal, week, todayGoal, said, offers, asked, walked, stepsByDay)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Записи открытого дня — для правил, которым нужен день прямо сейчас.
     *
     * Пока база не ответила на только что открытый день, в [data] ещё лежит
     * прошлый, и его записи сюда не годятся.
     */
    private val shownEntries: List<DiaryEntryEntity>
        get() = data.value?.takeIf { it.date == shownDate }?.day?.entries.orEmpty()

    private val typing = combine(input, answer, scanned, overlay, assist, ::TypingState)

    /** Вложенный combine: у типизированного [combine] потолок в пять потоков, а их больше. */
    private val typingAndReplies = combine(typing, sessionReplies, outboxItem, pickedMeal) { typed, replies, pending, meal ->
        Session(typed, replies, pending, meal)
    }

    /** Облачки и сигнал сцене — одним потоком: у [combine] потолок в пять. */
    private val stage = combine(speech, cue) { lines, signal -> lines to signal }

    /** Кому сейчас пишут; `null` — себе. */
    private val sendingTo = MutableStateFlow<NearbyPerson?>(null)

    /**
     * Кто рядом и кому пишем — одним потоком.
     *
     * Поиск идёт, пока на состояние экрана подписаны: `WhileSubscribed` снимает
     * подписку вместе с экраном, и в кармане телефон соседей не ищет.
     *
     * `onStart` с пустым списком обязателен: пока никого не нашли, Bonjour молчит,
     * а `combine` не отдаёт состояние, пока не отозвался каждый поток. Без этого
     * экран оставался пустым целиком — при живом приложении и работающем поиске.
     */
    /**
     * Адрес страницы дневника — пока сервер поднят и телефон в Wi-Fi. Нужен сразу,
     * в момент «Отправить», поэтому держится состоянием.
     */
    private val pageAddress = (peers?.address() ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val nearby = combine(
        (peers?.nearby() ?: flowOf(emptyList())).onStart { emit(emptyList()) },
        sendingTo,
        browsers?.online ?: flowOf(0),
        pageAddress,
        ::Around,
    )

    /** Что вокруг телефона: соседи, кому пишем, браузеры на связи и адрес страницы. */
    private data class Around(
        val people: List<NearbyPerson>,
        val writingTo: NearbyPerson?,
        val browsersOnline: Int,
        val address: String?,
    )

    val state = combine(typingAndReplies, data, personalState, stage, nearby) { session, loaded, models, scene, near ->
        val (typed, replies, pending, meal) = session
        val (people, writingTo, browsersOnline, address) = near
        if (loaded == null) {
            return@combine DiaryUiState(input = typed.input, overlay = typed.overlay, keepScreenOn = browsersOnline > 0)
        }
        val day = loaded.date.toEpochDays()
        val pastMeal = if (loaded.date < loaded.today) meal ?: defaultPastMeal(loaded.day.entries) else null
        val row = composerRow(typed, models, pastMeal)
        DiaryUiState(
            input = typed.input,
            feed = buildFeed(
                loaded = loaded,
                models = models,
                typed = typed,
                replies = replies.filter { it.day == day }.map { it.item },
                // Очередь отправки — разговор о сегодняшнем, во вчерашней переписке ей не место.
                pending = pending.takeIf { pastMeal == null },
                pastMeal = pastMeal,
                // Соседи — разговор о сейчас: во вчерашней переписке им не место,
                // как и очереди отправки.
                nearby = people.takeIf { pastMeal == null && writingTo == null }.orEmpty(),
                address = address,
            ),
            composer = row,
            action = composerAction(typed.input),
            totals = loaded.day.totals,
            fiber = loaded.day.entries.fiber(),
            week = loaded.week,
            weekSteps = loaded.weekSteps.filterValues { it > 0 },
            stepsGoal = loaded.walked?.goal ?: DEFAULT_STEPS_GOAL,
            date = loaded.date,
            today = loaded.today,
            goal = loaded.goal,
            todayGoal = loaded.todayGoal,
            tdee = models.tdee,
            lastWeightGrams = models.lastWeightGrams,
            overlay = typed.overlay,
            pastMeal = pastMeal,
            sendingTo = writingTo?.name,
            speech = scene.first,
            cue = scene.second,
            night = isNight(hourNow()),
            keepScreenOn = browsersOnline > 0,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryUiState())

    /**
     * Экран снова на виду. Если за это время наступил новый день — переключаемся
     * на него. И заново спрашиваем сервер: пока экран лежал в фоне, человек мог
     * прийти домой, в сеть с сервером, — или уйти из неё.
     */
    fun onResume() {
        val now = clock.todayIn(zone)
        if (now != date.value) date.value = now
        refreshOutbox()
        // Человек снова здесь: всё написанное персонажами он видит в ленте, а планировщик
        // знает, что мешать сейчас не надо.
        // Не было три часа и больше — встречают.
        viewModelScope.launch { show(brain.onAppOpened(clock.now())) }
    }

    /** Дневник открыт тапом по уведомлению персонажа: автор радуется, что его прочитали. */
    fun onOpenedFromMessage(messageId: Long) {
        viewModelScope.launch { show(brain.react(MascotEvent.Opened(messageId), clock.now())) }
    }

    // --- Сцена: облачка и сигналы -------------------------------------------------------

    /** Касание персонажа: тык, трюк, подняли, отпустили, погладили, разбудили. */
    fun onMascotEvent(event: MascotEvent) {
        viewModelScope.launch { show(brain.react(event, clock.now())) }
    }

    /** Телефон тряхнули — голова кружится у всех. */
    fun onPhoneShaken() = onMascotEvent(MascotEvent.Shaken)

    /** Тап по клетчатке в шапке: Зёрнышко — её хозяин — говорит, что это за число. */
    fun onFiberTapped() {
        val goal = data.value?.takeIf { it.date == shownDate }?.goal
        say(Speech(Mascot.GRAIN, fiberSpeech(shownEntries.fiber(), fiberGoal(goal))))
    }

    private fun show(reaction: Reaction?) {
        reaction ?: return
        reaction.cue?.let { cue.value = CueUi(++stageIds, it.kind, it.mascot) }
        reaction.speech?.let(::say)
    }

    /** Облачко над персонажем. У каждого одно: новое сменяет прошлое, а не встаёт стопкой. */
    private fun say(line: Speech) {
        val bubble = SpeechUi(++stageIds, line.mascot, line.text)
        speech.value = speech.value.filterNot { it.mascot == line.mascot } + bubble
        viewModelScope.launch {
            delay(speechDurationMs(line.text))
            speech.value = speech.value.filterNot { it.id == bubble.id }
        }
    }

    /** Калории открытого дня прямо сейчас — точка отсчёта для «закрыл цель». */
    private fun dayKcal(): Int = shownEntries.sumOf { it.totals().kcal }

    /**
     * Еда записана: хозяин макроса гордится, а запись, точно закрывшая цель, —
     * праздник всех троих. Только сегодня: дописанный вчерашний ужин — не событие.
     */
    private suspend fun reactToMeal(entryId: Long, kcalBefore: Int, today: Boolean) {
        if (!today) return
        val entry = diary.entry(entryId) ?: return
        val goal = data.value?.goal?.kcal?.takeIf { it > 0 }
        val reached = goal != null && reachedGoal(kcalBefore, kcalBefore + entry.totals().kcal, goal)
        show(brain.react(if (reached) MascotEvent.GoalReached else MascotEvent.Ate(entry), clock.now()))
    }

    /**
     * Открыть переписку дня — тапом по столбику недели.
     *
     * Сегодняшний день открывается как `null`, а не датой: тогда он сам
     * перейдёт на завтра в полночь, а не застрянет вчерашним.
     */
    fun onSelectDay(epochDay: Long) {
        picked.value = LocalDate.fromEpochDays(epochDay).takeIf { epochDay < today }
        pickedMeal.value = null
    }

    /** В какой приём писать прошлый день: чипсы над строкой ввода. */
    fun onPickMeal(meal: MealType) {
        pickedMeal.value = meal
    }

    /**
     * Что нашлось на [query].
     *
     * Запрос хранится вместе с выдачей намеренно: ответ показывается до тех пор,
     * пока не придёт следующий, и реплика «не нашлось» обязана называть тот
     * запрос, на который она отвечает, а не то, что стоит в строке сейчас.
     */
    private class Answer(val query: String, val items: List<ResolvedItem>)

    /** Открытый день так, как его отдала база, — и сегодняшнее, нужное шапке и шиту цели. */
    private class DayData(
        val date: LocalDate,
        val today: LocalDate,
        val day: DayTotals,
        val goal: DailyGoalEntity?,
        val week: List<DaySummary>,
        val todayGoal: DailyGoalEntity?,
        val said: List<MascotMessageEntity>,
        val offers: List<PeerOfferEntity> = emptyList(),
        val browsers: List<WebClientEntity> = emptyList(),
        val walked: DayWalk? = null,
        val weekSteps: Map<Long, Int> = emptyMap(),
    )

    /** Шаги дня и цель к ним. */
    private data class DayWalk(val steps: DaySteps, val goal: Int)

    /** Шаги дня с целью из профиля; без шагомера и дорожки — `null` сразу, ждать нечего. */
    private fun walkOf(day: Long): Flow<DayWalk?> {
        val steps = daySteps ?: return flowOf(null)
        return combine(steps(day).distinctUntilChanged(), brain.profile.map { it.stepsGoalOrDefault }.distinctUntilChanged(), ::DayWalk)
    }

    /** Рост из профиля — помощник цели подставляет его сам. */
    val heightCm: StateFlow<Int?> = brain.profile.map { it.heightCm }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Что в переписке дня сказали не записи: персонажи, соседи и браузеры, — и шаги дня. */
    private data class Said(
        val said: List<MascotMessageEntity>,
        val offers: List<PeerOfferEntity>,
        val browsers: List<WebClientEntity>,
        val walked: DayWalk?,
        val weekSteps: Map<Long, Int>,
    )

    /** Реплика сессии и день, в переписке которого она стоит. */
    private data class DayReply(val day: Long, val item: FeedItem)

    /** То, что живёт только в этой сессии экрана: набор, реплики, очередь, приём прошлого дня. */
    private data class Session(
        val typed: TypingState,
        val replies: List<DayReply>,
        val outbox: FeedItem.Outbox?,
        val meal: MealType?,
    )

    /**
     * Нейросеть в этой сессии экрана.
     *
     * @param ready модель на телефоне есть — без неё реплики и строка ввода ведут себя как прежде
     * @param thinking фраза, которую она разбирает прямо сейчас
     * @param failed фразы, которые она уже не разобрала: второй раз их не предлагаем
     */
    private data class AssistState(
        val ready: Boolean = false,
        val thinking: String? = null,
        val failed: Set<String> = emptySet(),
    ) {
        fun about(query: String): Assist = when {
            !ready -> Assist.NONE
            thinking == query -> Assist.THINKING
            query in failed -> Assist.FAILED
            else -> Assist.OFFER
        }
    }

    private class TypingState(
        val input: String,
        val answer: Answer?,
        val scanned: ResolvedItem?,
        val overlay: Overlay,
        val assist: AssistState,
    ) {
        val suggestions: List<ResolvedItem> get() = answer?.items.orEmpty()

        /** Вес распознаётся до поиска: «вес 82,4» — это не блюдо. */
        val weightGrams: Int? = parseWeight(input)

        /**
         * Ничего не нашлось — это ответ, и его надо показать, а не молчать.
         *
         * Ответ, а не догадка: пока поиск по набранному не завершился, реплики
         * нет. Раньше она выводилась из пустой выдачи, и на каждую букву успевала
         * появиться в окно дебаунса, спрятаться на время поиска и появиться снова.
         */
        val notFound: Answer?
            get() = answer?.takeIf {
                it.items.isEmpty() && input.isNotBlank() && weightGrams == null && scanned == null
            }
    }

    init {
        // Страница дневника поднялась — Зёрнышко расскажет о ней. Один раз за всю жизнь
        // и в свою очередь: когда и сколько, решает мозг персонажей.
        viewModelScope.launch {
            brain.tellWebAddress(pageAddress.filterNotNull().first(), clock)
        }
        // Предложения пересобираются на паузу в наборе, а не на каждый символ:
        // поиск ходит в базу, и дёргать её на «г», «гр», «гре» незачем.
        viewModelScope.launch {
            input
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .mapLatest { text ->
                    // Вес в поиск не уходит: «82.4 кг» не блюдо, и выдача по нему
                    // была бы шумом поверх единственного осмысленного действия.
                    // Поиск не идёт и по строке, дописываемой к отсканированному:
                    // товар уже опознан кодом точнее любого совпадения по названию,
                    // а выдача поверх него была бы шумом.
                    // И по строке без единой буквы: «200» — начало «200 г гречки»,
                    // а не блюдо, и ответ «ничего не нашлось» перебивал бы человека
                    // на полуслове.
                    // И по вопросу, когда на него есть кому ответить: «сколько я
                    // съел вчера?» — не блюдо, и «ничего не нашлось» было бы глупостью.
                    // И по просьбе об адресе страницы: «компьютер» — не еда.
                    if (text.isBlank() || parseWeight(text) != null || text.continuesScan() ||
                        text.none(Char::isLetter) || (assist.value.ready && isQuestion(text)) ||
                        (peers != null && asksForPage(text)) || asksForTreadmill(text) || asksForSteps(text)
                    ) {
                        null
                    } else {
                        val items = try {
                            resolver.suggest(text, context())
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            // Поиск идёт на каждый ввод. Сбой — это пустой список
                            // предложений, а не падение посреди набора.
                            Log.e(TAG, "поиск «$text» не удался", error)
                            emptyList()
                        }
                        Answer(text, items)
                    }
                }
                .collect { found ->
                    answer.value = found
                    // Каждый ответ закрывает ожидание: строку могли переписать, и тогда
                    // отправленного в ней уже нет.
                    val waiting = submitted
                    submitted = null
                    if (found != null && found.query == waiting) recordFirst(found)
                }
        }

        // Есть ли нейросеть, спрашивается один раз: модель не появляется
        // и не пропадает посреди сессии экрана.
        assistant?.let { model ->
            viewModelScope.launch {
                val ready = try {
                    model.ready()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Log.e(TAG, "нейросеть не ответила, есть ли она", error)
                    false
                }
                assist.value = assist.value.copy(ready = ready)
            }
        }

        // Модели пересчитываются от любого изменения дня или цели. Ставить пересчёт
        // в каждый обработчик по отдельности означало бы рано или поздно забыть один
        // из них и показывать вчерашние предсказания.
        viewModelScope.launch {
            combine(
                data.filterNotNull().map { Triple(it.date, it.day, it.goal) }.distinctUntilChanged(),
                // Подсказки прошлого дня — для выбранного приёма: «обычно на ужин».
                pickedMeal,
            ) { (_, dayTotals, _), _ -> dayTotals }
                .collect { dayTotals -> recompute(dayTotals.entries) }
        }
    }

    // --- Сборка ленты -----------------------------------------------------------------

    /**
     * Лента целиком.
     *
     * Порядок реплик — порядок разговора: сначала то, что записал человек, потом
     * то, что на это отвечает приложение. Поэтому остаток и перебор стоят в конце,
     * а сводка дня — в начале и только пока записей нет.
     *
     * Прошлый день — та же переписка, но без вопросов «про сейчас»: пропущенный
     * обед, остаток и утренняя сводка про вчера звучат поздно и неправдой.
     *
     * Персонажи говорят по одному: пока вопрос ждёт ответа (см. [awaitsAnswer]),
     * пропущенный приём, остаток и перебор ждут вместе с ним — вопрос не тонет
     * среди подсказок.
     *
     * @param pastMeal приём, в который пишется прошлый день; `null` — открыт сегодняшний
     */
    private fun buildFeed(
        loaded: DayData,
        models: PersonalState,
        typed: TypingState,
        replies: List<FeedItem>,
        pending: FeedItem.Outbox?,
        pastMeal: MealType?,
        nearby: List<NearbyPerson> = emptyList(),
        address: String? = null,
    ): List<FeedItem> {
        val now = nowMillis()
        val hour = hourNow()
        val isToday = pastMeal == null
        val dayTotals = loaded.day
        val goal = loaded.goal
        val feed = mutableListOf<FeedItem>(FeedItem.DaySeparator(dayLabel(loaded.date, loaded.today)))

        if (dayTotals.entries.isEmpty()) {
            if (isToday) {
                daySummary(goal?.kcal?.takeIf { it > 0 }, loaded.week, models.dayOutline, hour)?.let { feed += it }
            } else {
                feed += FeedItem.EmptyDay(formatDayFull(loaded.date))
            }
        }

        val lastEntryAt = dayTotals.entries.maxOfOrNull { it.createdAt }
        // Предложение соседа держит слово наравне с вопросом персонажа: и то и другое
        // ждёт ответа, а персонажи говорят по одному.
        val waiting = isToday &&
            (
                awaitsAnswer(loaded.said, lastEntryAt) ||
                    awaitsOffer(loaded.offers, lastEntryAt) ||
                    awaitsBrowser(loaded.browsers, lastEntryAt)
                )
        val bubbles = groupIntoBubbles(dayTotals.entries, zone, now, activeMeal = pastMeal)
        // Убранное корзинкой в переписку не встаёт; ждать ответа оно не может — убирается только отвеченное.
        val said = loaded.said.map { it.toFeedItem(address) } +
            loaded.offers.filter { it.hiddenAt == null }.map { it.toFeedItem() } +
            loaded.browsers.filter { it.hiddenAt == null }.map { it.toFeedItem() }
        val body = interleave(bubbles, said, zone).toMutableList()
        // Отвеченный «Обед был?» стоит там же, где был задан; новый — только когда слово свободно.
        val answered = replies.filterIsInstance<FeedItem.Gap>()
        val live = models.mealGap
            ?.takeIf { gap -> isToday && !waiting && answered.none { it.gap.meal == gap.meal } }
            ?.let { FeedItem.Gap(it) }
        // Позже других — первым: место считается по пузырькам, и порядок вопросов не путается.
        (answered + listOfNotNull(live)).sortedByDescending { it.gap.typicalHour }.forEach { gap ->
            body.add(gapPosition(body, gap.gap, zone), gap)
        }
        feed += body
        stepsOf(loaded)?.let { feed += it }
        feed += replies.filterNot { it is FeedItem.Gap }
        // Предложение отправить — тоже реплика: пока вопрос ждёт ответа, оно ждёт с ним.
        pending?.takeUnless { waiting }?.let { feed += it }
        // Соседи ждут вместе с остальными: персонажи говорят по одному.
        if (!waiting && nearby.isNotEmpty()) feed += FeedItem.Nearby(nearby)

        // Перебор вытесняет остаток, а не дополняет его: это один и тот же ответ
        // на один и тот же вопрос, и показывать оба значило бы спорить с собой.
        val target = goal?.kcal?.takeIf { it > 0 }
        when {
            // Подсказки и итоги ждут ответа на вопрос.
            waiting -> Unit
            target != null && dayTotals.totals.kcal > target ->
                feed += FeedItem.Over(dayTotals.totals.kcal - target, past = !isToday)
            isToday -> models.dayPlan?.let { feed += FeedItem.Remaining(it, nextMealTitle(hour)) }
        }

        typed.notFound?.let {
            val query = it.query.trim()
            feed += FeedItem.NotFound(query, typed.assist.about(query))
        }

        return feed
    }

    /**
     * Плашка шагов дня — после записей. Сегодня она стоит и при нуле, если шагомер телефона отдаёт шаги:
     * утром ей есть куда расти. Прошлый день без шагов — без плашки: там уже ничего не прибавится.
     */
    private fun stepsOf(loaded: DayData): FeedItem.Steps? {
        val (steps, goal) = loaded.walked ?: return null
        val shown = if (loaded.date == loaded.today) steps.phone != null || steps.treadmill > 0 else steps.total > 0
        return if (shown) FeedItem.Steps(steps = steps.total, goal = goal) else null
    }

    /**
     * Ряд над строкой ввода.
     *
     * Порядок проверок — порядок приоритета: вес важнее поиска, потому что он
     * уже однозначен; скан важнее предсказаний, потому что человек только что
     * навёл камеру.
     */
    private fun composerRow(typed: TypingState, models: PersonalState, pastMeal: MealType?): ComposerRow = when {
        // Скан идёт первым: пока в строке стоит опознанный товар, всё остальное
        // в этом ряду было бы предложением заменить точное совпадение догадкой.
        typed.scanned != null -> ComposerRow.Scanned(typed.scanned.withTypedGrams(typed.input))
        typed.weightGrams != null -> ComposerRow.Weight(typed.weightGrams)
        typed.suggestions.isNotEmpty() -> ComposerRow.Results(typed.suggestions)
        typed.input.isBlank() && models.predictions.isNotEmpty() ->
            ComposerRow.Predictions(models.predictions, meal = pastMeal)
        else -> ComposerRow.None
    }

    /**
     * Пересчёт всех шести моделей.
     *
     * История сбрасывается в самом начале: любое изменение дня делает её счётчики
     * неверными, а модели, посчитанные по устаревшим счётчикам, врут незаметно.
     */
    private suspend fun recompute(entries: List<DiaryEntryEntity>) {
        try {
            personal.invalidate()
            val context = context(prevRefKey = entries.lastRefKey())
            // Пропуск, остаток и раскладка дня — про «сейчас», и считать их
            // для прошлого дня незачем: в его переписке их нет.
            val forToday = !isPast
            personalState.value = PersonalState(
                predictions = personal.predictions(context),
                mealGap = if (forToday) personal.mealGap(context, entries) else null,
                dayPlan = if (forToday) personal.remainingPlan(context, entries) else null,
                // Расход нужен шиту цели, а цель всегда правится с сегодня.
                tdee = personal.tdee(today),
                dayOutline = if (forToday) personal.dayOutline(context) else null,
                lastWeightGrams = personal.latestWeight(today),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Персонализация — надстройка. Её отказ не должен уносить дневник.
            Log.e(TAG, "пересчёт моделей не удался", error)
            personalState.value = PersonalState()
        }
    }

    /** Строка продолжает отсканированное: к названию дописывают вес. */
    private fun String.continuesScan(): Boolean =
        scanned.value?.let { startsWith(it.sourceText) } == true

    fun onInputChange(text: String) {
        input.value = text
        if (text.isBlank()) answer.value = null

        // Отсканированное держится, пока в строке стоит его название: человек
        // дописывает к нему вес, а не ищет другое блюдо. Стёр название — значит,
        // от скана ушёл, и чипс уходит вместе с ним.
        val scan = scanned.value
        if (scan != null && !text.startsWith(scan.sourceText)) scanned.value = null
    }

    /**
     * Кладёт выбранное предложение в сегодняшний день и очищает поле.
     *
     * Поле чистится сразу, не дожидаясь записи: следующее блюдо можно начинать
     * набирать немедленно, а вставка идёт своим чередом.
     *
     * @param meal куда положить. Задаётся, когда человек закрывает пропущенный
     *        приём пищи: в 16:00 добавленный обед должен остаться обедом.
     */
    fun onPick(item: ResolvedItem, meal: MealType? = null) {
        val candidate = item.candidate ?: return
        if (item.grams <= 0) return
        if (sendToPeerInstead(candidate, item.grams)) return

        val shown = answer.value?.items.orEmpty()
        val pickedIndex = shown.indexOfFirst { it.candidate?.ref == candidate.ref }
        val query = input.value
        val proposed = item.source == EntrySource.LLM

        // Предложения нейросети — это несколько блюд одной фразы, а не варианты
        // одного: «борщ и кефир» записываются двумя тапами, и после первого
        // кефир обязан остаться на месте вместе со строкой.
        val rest = if (proposed) shown - item else emptyList()
        if (rest.isEmpty()) {
            input.value = ""
            answer.value = null
        } else {
            answer.value = Answer(query, rest)
        }
        val before = dayKcal()
        val today = !isPast

        viewModelScope.launch {
            val context = context()
            val target = meal ?: context.meal
            val id = diary.add(
                candidate = candidate,
                grams = item.grams,
                meal = target,
                date = context.dateEpochDay,
                source = item.source,
                now = entryTime(target),
            )
            // Выбор из предложений нейросети ранжированию не урок: она показывала
            // не выдачу поиска, и правильного места в ней у продукта не было.
            if (pickedIndex >= 0 && !proposed) {
                learn(query, shown, pickedIndex, item, candidate, context)
            }
            reactToMeal(id, before, today)
        }
    }

    /**
     * Добавление предсказанного продукта — идеи 1, 4 и 5.
     *
     * Ранжирующая модель об этом не узнаёт намеренно: человек выбирал не из выдачи
     * поиска, и считать это исправлением ранжирования значило бы учить её на том,
     * чего она не показывала.
     */
    fun onPickPrediction(candidate: FoodCandidate, meal: MealType? = null) {
        val grams = candidate.servingG ?: return
        if (grams <= 0) return
        // В режиме отправки еда уходит соседу, а в свой дневник не попадает:
        // отправитель её не ел.
        if (sendToPeerInstead(candidate, grams)) return
        val before = dayKcal()
        val today = !isPast

        viewModelScope.launch {
            val context = context()
            val target = meal ?: context.meal
            val id = diary.add(
                candidate = candidate,
                grams = grams,
                meal = target,
                date = context.dateEpochDay,
                source = EntrySource.MANUAL,
                now = entryTime(target),
            )
            reactToMeal(id, before, today)
        }
    }

    /** Журнал подтверждения плюс шаг обучения ранжирования и памяти порций. */
    private suspend fun learn(
        query: String,
        shown: List<ResolvedItem>,
        pickedIndex: Int,
        item: ResolvedItem,
        candidate: FoodCandidate,
        context: PersonalContext,
    ) {
        personal.onConfirmed(
            ConfirmedPick(
                context = context,
                query = query,
                shown = shown.mapNotNull { suggestion ->
                    suggestion.candidate?.let { ShownCandidate(it.ref, it.exactMatch) }
                },
                pickedIndex = pickedIndex,
                // Вес, который подставили мы сами, наблюдением не является:
                // запомнив его, модель выучила бы собственную догадку.
                portion = item.portionUnit
                    ?.takeUnless { item.gramsGuessed }
                    ?.let { unit -> PortionObservation(candidate.ref, unit, item.gramsPerUnit) },
                now = nowMillis(),
            )
        )
    }

    // --- Нейросеть ------------------------------------------------------------------------

    /**
     * «Разобрать нейросетью» в реплике «не нашлось».
     *
     * Предложения встают чипсами над строкой ввода — туда же, где стоит выдача
     * поиска, — и записываются только тапом. Пока модель думала, человек мог
     * переписать строку: тогда ответ про старую фразу уже не нужен.
     */
    fun onAskAssistant(query: String) {
        val model = assistant ?: return
        if (!assist.value.ready || assist.value.thinking != null) return
        assist.value = assist.value.copy(thinking = query)

        viewModelScope.launch {
            val items = try {
                model.propose(query).filter { it.candidate != null && it.grams > 0 }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Модель занята, не скачана, ответила кашей — для человека всё это
                // одно: не разобрала. Падать посреди дневника из-за неё нельзя.
                Log.e(TAG, "нейросеть не разобрала «$query»", error)
                emptyList()
            }
            val state = assist.value
            assist.value = state.copy(
                thinking = null,
                failed = if (items.isEmpty()) state.failed + query else state.failed,
            )
            val current = input.value
            if (items.isNotEmpty() && current.trim() == query) answer.value = Answer(current, items)
        }
    }

    /**
     * Вопрос к дневнику: «сколько белка за неделю?», «что я ел вчера?».
     *
     * Вопрос и ответ встают в ленту открытого дня как разговор этой сессии,
     * а не как записи: завтра вчерашний ответ «осталось 400 ккал» был бы неправдой.
     */
    private fun askDiary(question: String) {
        val model = assistant ?: return
        input.value = ""
        answer.value = null

        val time = formatTime(nowMillis(), zone)
        val asked = FeedItem.Question(nextReplyId(), question.trim(), time)
        val reply = FeedItem.Answer(nextReplyId(), text = "", time = time, done = false)
        val day = shownDay
        sessionReplies.value += listOf(DayReply(day, asked), DayReply(day, reply))

        viewModelScope.launch {
            var text = ""
            try {
                model.answer(asked.text).collect { partial ->
                    text = partial
                    updateAnswer(reply.id) { it.copy(text = partial) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "нейросеть не ответила на «${asked.text}»", error)
            }
            val final = text.trim().ifEmpty { NO_ANSWER }
            updateAnswer(reply.id) { it.copy(text = final, time = formatTime(nowMillis(), zone), done = true) }
        }
    }

    private fun updateAnswer(id: Int, change: (FeedItem.Answer) -> FeedItem.Answer) {
        sessionReplies.value = sessionReplies.value.map { reply ->
            val item = reply.item
            if (item is FeedItem.Answer && item.id == id) reply.copy(item = change(item)) else reply
        }
    }

    // --- Скан штрих-кода --------------------------------------------------------------

    fun onOpenScan() {
        overlay.value = Overlay.Scan()
    }

    /**
     * Код распознан — дальше цепочка: свои продукты, справочник, кэш, сеть.
     *
     * Найденное показывается чипсом и ждёт тапа, а не падает в дневник само.
     * Скан опознаёт товар, но не знает, сколько его съели, и молча записать
     * целую пачку было бы хуже, чем не записать ничего.
     */
    fun onScanned(gtin: String) {
        // Окно не закрывается, пока идёт поиск: он доходит до сети и длится
        // секунды, а закрытое окно на это время показывало дневник, и промах
        // открывал уже второе окно со своей камерой — экран мигал.
        val searching = Overlay.Scan(ScanStep.Searching(gtin))
        overlay.value = searching
        scanned.value = null

        viewModelScope.launch {
            val lookup = try {
                food.byBarcode(gtin)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Сюда входит и отсутствие сети. Промах и сбой ведут в одну и ту же
                // форму, и различать их перед человеком незачем.
                Log.e(TAG, "поиск по коду $gtin не удался", error)
                BarcodeLookup.Miss
            }

            // Окно могли закрыть посреди поиска — тогда промах его не открывает.
            val stillWaiting = overlay.value == searching

            when (lookup) {
                // Промах ведёт прямо в карточку продукта в том же окне: код уже
                // известен, остальное человек снимет камерой или впишет сам.
                BarcodeLookup.Miss ->
                    if (stillWaiting) overlay.value = Overlay.Scan(ScanStep.NewProduct(gtin))
                // Известное название встаёт в карточку сразу: его не надо ни снимать,
                // ни вписывать, а справочник по нему предложит похожий.
                is BarcodeLookup.NameOnly ->
                    if (stillWaiting) overlay.value = Overlay.Scan(ScanStep.NewProduct(gtin, name = lookup.name))
                is BarcodeLookup.Found -> {
                    if (stillWaiting) overlay.value = Overlay.None
                    // Название уезжает в поле ввода, курсор — за ним. Скан опознал
                    // товар, но не знает, сколько его съели, и дописать «150» к уже
                    // готовой строке быстрее, чем открывать отдельный шит веса.
                    // Чипс при этом остаётся и считает калории от набранного.
                    scanned.value = lookup.candidate.toScannedItem(EntrySource.BARCODE)
                    input.value = "${lookup.candidate.displayName} "
                }
            }
        }
    }

    /** Тап по отсканированному. Ранжирующая модель об этом не узнаёт — см. [ComposerRow.Scanned]. */
    fun onPickScanned(item: ResolvedItem) {
        val candidate = item.candidate ?: return
        if (item.grams <= 0) return

        scanned.value = null
        input.value = ""
        val before = dayKcal()
        val today = !isPast
        viewModelScope.launch {
            val context = context()
            val id = diary.add(
                candidate = candidate,
                grams = item.grams,
                meal = context.meal,
                date = context.dateEpochDay,
                source = item.source,
                now = entryTime(context.meal),
            )
            reactToMeal(id, before, today)
        }
    }

    /**
     * Сохранение продукта, заведённого руками поверх камеры.
     *
     * Продукт оседает локально и находится мгновенно навсегда, а копия уходит
     * в очередь отправки — чтобы следующий человек с той же пачкой заводить
     * его уже не стал. Валидация цифр осталась на экране: сюда попадает только
     * прошедшее её.
     *
     * В дневник продукт сам не падает: он показывается чипсом и ждёт тапа.
     * Экран съёмки знает, что это за товар, но не знает, сколько его съели.
     */
    fun onSaveNewProduct(gtin: String?, name: String, nutriments: Nutriments) {
        overlay.value = Overlay.None
        onScanFinished()
        refreshOutbox()

        viewModelScope.launch {
            val candidate = try {
                food.saveOwnProduct(gtin, name, nutriments, nowMillis())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "не удалось сохранить продукт $gtin", error)
                return@launch
            }

            // Тот же путь, что и у скана по коду: название уезжает в строку
            // ввода, курсор за ним, чипс остаётся и считает от набранного веса.
            // Человек только что заполнил карточку продукта — заставлять его
            // после этого ещё и искать его в поиске было бы издевательством.
            scanned.value = candidate.toScannedItem(EntrySource.MANUAL)
            input.value = "${candidate.displayName} "
            onContributionQueued()
        }
    }

    /**
     * Название с пачки — в справочник: вдруг такой продукт там уже есть.
     *
     * Промах по коду и нечитаемая таблица — частый исход, а «Творог 5%» в справочнике
     * есть почти всегда. Поиск тот же, что у строки ввода: по словам, а не нашлось —
     * по смыслу. Найденное карточка только предлагает; сбой — значит, предлагать нечего.
     */
    suspend fun matchLabelName(query: String): FoodCandidate? =
        try {
            food.search(query, limit = 1).firstOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.e(TAG, "справочник не ответил на «$query»", error)
            null
        }

    /**
     * Человек взял похожий из справочника вместо того, чтобы заводить продукт.
     *
     * Ничего не сохраняется: числа у похожего справочные, а не с этой пачки, и
     * записать их под её кодом — отправить в общую базу выдумку. Дальше тот же путь,
     * что у найденного по коду: название в строке ввода, чипс ждёт веса.
     */
    fun onPickLabelMatch(candidate: FoodCandidate) {
        onDismissOverlay()
        scanned.value = candidate.toScannedItem(EntrySource.VISION)
        input.value = "${candidate.displayName} "
    }

    /**
     * «Нет штрих-кода» в сканере: снимать будут этикетку.
     *
     * У развесного, домашнего, вскрытой упаковки кода нет вовсе, а таблица
     * пищевой ценности есть. Карточка открывается в том же окне, камера не гаснет.
     *
     * Только с шага кода: прочитанный в ту же секунду код побеждает нажатие —
     * найдётся товар, карточка не нужна, а промах откроет её сам, уже с кодом.
     */
    fun onNoBarcode() {
        if (overlay.value == Overlay.Scan(ScanStep.Barcode)) {
            overlay.value = Overlay.Scan(ScanStep.NewProduct())
        }
    }

    // --- Свой продукт из ненайденного слова ---------------------------------------------

    /**
     * «Добавить» на ненайденном слове: форма без камеры, название уже вписано.
     *
     * Домашнее и развесное снимать нечем, а цифры пирога человек знает сам — камера тут
     * только мешала бы. Штрих-код снимается по кнопке в форме, см. [onScanProductCode].
     */
    fun onAddProduct(query: String) {
        overlay.value = Overlay.AddProduct(newProductFormFor(query))
    }

    fun onProductForm(form: NewProductForm) {
        val open = overlay.value as? Overlay.AddProduct ?: return
        overlay.value = open.copy(form = form)
    }

    /** Камера поверх формы — только ради кода; набранное ждёт под ней. */
    fun onScanProductCode() {
        val open = overlay.value as? Overlay.AddProduct ?: return
        overlay.value = open.copy(scanning = true)
    }

    fun onProductCode(gtin: String) {
        val open = overlay.value as? Overlay.AddProduct ?: return
        if (!open.scanning) return
        overlay.value = Overlay.AddProduct(open.form.copy(barcode = gtin))
    }

    fun onCancelProductCode() {
        val open = overlay.value as? Overlay.AddProduct ?: return
        overlay.value = open.copy(scanning = false)
    }

    /** Тот же путь, что у карточки сканера: продукт оседает локально и встаёт в строку ввода. */
    fun onSaveAddedProduct() {
        val form = (overlay.value as? Overlay.AddProduct)?.form ?: return
        val nutriments = form.nutriments?.takeIf { form.canSave } ?: return
        onSaveNewProduct(form.gtin, form.name.trim(), nutriments)
    }

    // --- Проверка распознавания этикетки ----------------------------------------------

    fun onOpenLabelDebug() {
        overlay.value = Overlay.LabelDebug
    }

    /**
     * Тестовая съёмка закончилась кнопкой «Готово».
     *
     * В дневник и в справочник отсюда не уходит ничего — смысл ровно в одном:
     * сложить разбор в лог целиком, чтобы его можно было разглядывать после,
     * не держа пачку перед камерой.
     */
    fun onLabelDebugRead(reading: LabelReading) {
        overlay.value = Overlay.None
        onScanFinished()
        refreshOutbox()

        val draft = reading.draft
        Log.i(TAG, "этикетка: ${reading.trace.route.title}, " +
            "${if (reading.confident) "сошлось" else "не сошлось"}; " +
            "ккал=${draft.kcal100} Б=${draft.prot100} Ж=${draft.fat100} У=${draft.carb100} (сотые грамма)")
        Log.i(TAG, "этикетка, строки: ${reading.trace.lines.joinToString(" | ")}")
        if (reading.trace.zeroedLabels.isNotEmpty()) {
            Log.i(TAG, "этикетка, принято за ноль: ${reading.trace.zeroedLabels.joinToString(", ")}")
        }
    }

    /**
     * Чипс для найденного товара.
     *
     * Вес берётся из типичной порции, а когда её нет — сто грамм, и это честно
     * помечается [ResolvedItem.gramsGuessed]: число подставили мы, а не человек,
     * и памяти личных порций такое наблюдение не годится.
     */
    private fun FoodCandidate.toScannedItem(source: EntrySource) = ResolvedItem(
        sourceText = displayName,
        candidate = this,
        grams = servingG ?: DEFAULT_PORTION_G,
        confidence = 1f,
        source = source,
        // Порция продукта — не догадка: её указал человек, заводя продукт,
        // либо она пришла из справочника вместе с товаром. Сотня грамм на её
        // месте — как раз догадка, и это помечается честно.
        gramsGuessed = servingG == null,
    )

    // --- Правка записи: вес, приём пищи, удаление -------------------------------------

    fun onEditEntry(entry: DiaryEntryEntity) {
        overlay.value = Overlay.EditEntry(entry)
    }

    /**
     * Шит правки сохранён.
     *
     * Вес и приём пищи пишутся только если действительно изменились. Это не
     * экономия запросов: правка веса — самый чистый сигнал для памяти личных
     * порций, и записывать его на каждое закрытие шита значило бы подтверждать
     * подставленное число от имени человека.
     */
    fun onSaveEntry(entry: DiaryEntryEntity, grams: Int, meal: MealType) {
        overlay.value = Overlay.None
        if (grams <= 0) return

        viewModelScope.launch {
            if (grams != entry.grams) personal.onGramsEdited(entry.id, grams, nowMillis())
            if (meal != entry.meal) diary.moveToMeal(entry.id, meal)
        }
    }

    /**
     * Человек поправил сам продукт — цифры на 100 г, а не порцию.
     *
     * Шит остаётся открытым и уже с поправленным продуктом: порцию считают по новым
     * цифрам, а следующая правка сравнивается с ними, а не с тем, что было при открытии.
     */
    fun onCorrectProduct(entry: DiaryEntryEntity, name: String, per100: Nutriments) {
        overlay.value = Overlay.EditEntry(
            entry.copy(
                displayName = name.trim().ifBlank { entry.displayName },
                kcal100 = per100.kcal100,
                prot100 = per100.prot100,
                fat100 = per100.fat100,
                carb100 = per100.carb100,
                fiber100 = per100.fiber100,
            )
        )
        viewModelScope.launch {
            diary.correctProduct(entry, name, per100, nowMillis())
            // Правка продукта с кодом легла в очередь — Яйцо предложит ею поделиться.
            refreshOutbox()
        }
    }

    /**
     * Удаление — сразу и без вопроса, но с ответом «Удалил… Вернуть».
     *
     * Снимок берётся из дня, а не из шита: шит держит запись с момента открытия,
     * а вернуть надо ровно ту, что лежала в дневнике.
     */
    fun onDeleteEntry(entry: DiaryEntryEntity) {
        overlay.value = Overlay.None
        val snapshot = shownEntries.find { it.id == entry.id } ?: entry

        viewModelScope.launch {
            diary.delete(snapshot.id)
            sessionReplies.value += DayReply(
                day = snapshot.dateEpochDay,
                item = FeedItem.Deleted(
                    id = nextReplyId(),
                    entry = snapshot,
                    time = formatTime(nowMillis(), zone),
                ),
            )
        }
    }

    /**
     * «Вернуть»: запись встаёт обратно той же — с прежним id, приёмом и временем.
     *
     * Реплика остаётся в переписке, а кнопка гаснет сразу, до записи в базу: второй
     * тап по ней вставил бы запись с тем же ключом ещё раз.
     */
    fun onUndoDelete(item: FeedItem.Deleted) {
        val reply = sessionReplies.value.firstOrNull { (it.item as? FeedItem.Deleted)?.id == item.id } ?: return
        val deleted = reply.item as FeedItem.Deleted
        if (deleted.restored) return
        sessionReplies.value = sessionReplies.value.map { if (it === reply) it.copy(item = deleted.copy(restored = true)) else it }

        viewModelScope.launch {
            try {
                diary.restore(deleted.entry)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "не удалось вернуть запись ${deleted.entry.id}", error)
            }
        }
    }

    /**
     * «Отправить» — кнопкой у строки ввода или ✓ на клавиатуре, это одно и то же.
     *
     * Вес и отсканированный товар записываются сразу. Где есть нейросеть, фраза уходит
     * ей на разбор — как сообщение в мессенджере, — и разобранное встаёт чипсами.
     * Что уже стоит чипсами, принимается с первого: выдача поиска отсортирована, и человек
     * видит, что запишется, прямо над кнопкой, а другой вариант выбирает тапом. Блюда
     * из ответа нейросети записываются по одному, как и тапом.
     *
     * Поиск идёт с паузой, и в чипсах может стоять ответ на прошлую строку: «гречка 20»
     * вместо «гречка 200». Такой ответ не годится — запись ждёт поиска, см. [submitted].
     *
     * @return `true`, если что-то записано или отправлено; иначе просто прячется клавиатура
     */
    fun onSubmit(): Boolean {
        val text = input.value
        val scan = scanned.value
        val found = answer.value?.takeIf { it.query == text }
        val answered = found?.items.orEmpty().any { it.source == EntrySource.LLM }
        val canAsk = assist.value.about(text.trim()).let { it == Assist.OFFER || it == Assist.THINKING }
        return when {
            scan != null -> {
                onPickScanned(scan.withTypedGrams(text))
                true
            }
            parseWeight(text) != null -> {
                onLogWeight()
                true
            }
            peers != null && asksForPage(text) -> {
                tellPageAddress(pageAddress.value)
                true
            }
            asksForTreadmill(text) -> {
                input.value = ""
                answer.value = null
                treadmillAsks.trySend(Unit)
                true
            }
            asksForSteps(text) -> {
                input.value = ""
                answer.value = null
                stepsAsks.trySend(Unit)
                true
            }
            assist.value.ready && isQuestion(text) -> {
                askDiary(text)
                true
            }
            // «200» — начало «200 г гречки»: блюда ещё нет, и поиск по такой строке не идёт.
            text.none(Char::isLetter) -> false
            canAsk && !answered -> {
                onAskAssistant(text.trim())
                true
            }
            found != null -> recordFirst(found)
            else -> {
                submitted = text
                true
            }
        }
    }

    /** Принять то, что стоит первым чипсом. Записывать нечего — `false`. */
    private fun recordFirst(found: Answer): Boolean {
        val first = found.items.firstOrNull { it.candidate != null && it.grams > 0 } ?: return false
        onPick(first)
        return true
    }

    // --- Цель и вес -------------------------------------------------------------------

    fun onOpenGoal() {
        overlay.value = Overlay.Goal
    }

    fun onDismissOverlay() {
        val closing = overlay.value
        overlay.value = Overlay.None
        // Сессия съёмки этикетки пишется на диск при закрытии окна — её пора отправлять.
        val labelShot = closing is Overlay.Scan && closing.step is ScanStep.NewProduct
        if (labelShot || closing == Overlay.LabelDebug) {
            onScanFinished()
            refreshOutbox()
        }
    }

    // --- Очередь отправки ---------------------------------------------------------------

    /**
     * Пересчитать, что ждёт отправки.
     *
     * Сессия съёмки пишется на диск в отдельном потоке уже после закрытия экрана,
     * поэтому счёт берётся с паузой — иначе пузырёк не увидел бы только что снятое.
     *
     * Пузырёк стоит, только когда слать есть что и сервер отвечает. Служебное
     * сообщение в ленте уместно, лишь когда по нему можно что-то сделать: с молчащим
     * сервером «Отправить» кончалось бы «сервер не ответил», а отправку при появлении
     * сети воркеры и так делают сами.
     */
    private fun refreshOutbox() {
        val source = outbox ?: return
        outboxRefresh?.cancel()
        outboxRefresh = viewModelScope.launch {
            delay(OUTBOX_SETTLE_MS)
            val shown = try {
                val count = source.pending()
                count.takeUnless { it.isEmpty || !it.exceeds(source.declined()) || !source.reachable() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "очередь отправки не посчиталась", error)
                null
            }
            // Идущую отправку не перебиваем: её итог придёт ответом в ленте.
            if (outboxSending) return@launch
            // Номер держится, пока пузырёк на месте: новый ключ — новая реплика с пружиной.
            outboxItem.value = shown?.let { FeedItem.Outbox(it, sending = false, id = outboxItem.value?.id ?: nextReplyId()) }
        }
    }

    /** Последний пересчёт очереди: новый отменяет прошлый, ждущий ответа сервера. */
    private var outboxRefresh: Job? = null

    /** Идёт отправка по кнопке: второй тап и пересчёт очереди её не перебивают. */
    private var outboxSending = false

    /**
     * Тап по «Отправить»: та же отправка, что у воркеров, только с ответом в ленте.
     *
     * Пузырёк уходит в переписку сессии и остаётся там с погасшей кнопкой, ответ
     * встаёт под ним. Ключ тот же, так что на месте ничего не мигает.
     */
    fun onSendOutbox() {
        val source = outbox ?: return
        val current = outboxItem.value ?: return
        if (outboxSending) return
        outboxSending = true
        val sending = current.copy(sending = true)
        outboxItem.value = null
        sessionReplies.value += DayReply(today, sending)

        viewModelScope.launch {
            val result = try {
                source.send()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "отправка не удалась", error)
                OutboxResult.Unreachable
            }
            sessionReplies.value = sessionReplies.value.map { reply ->
                if (reply.item == sending) reply.copy(item = sending.copy(sending = false, done = true)) else reply
            }
            reply(
                when {
                    // Фоновая отправка могла успеть раньше кнопки — это не ошибка.
                    result is OutboxResult.Sent && result.scans + result.products == 0 ->
                        "Всё уже ушло на сервер"
                    result is OutboxResult.Sent ->
                        "Отправлено на сервер: ${describeOutbox(result.scans, result.products)}"
                    source.retriesInBackground -> "Сервер не ответил — отправлю позже сам"
                    else -> "Сервер не ответил — спрошу ещё раз позже"
                }
            )
            outboxSending = false
            refreshOutbox()
        }
    }

    /**
     * «Не сейчас»: пузырёк остаётся в переписке с отмеченным ответом, а отказ запоминается —
     * те же продукты Яйцо больше не предлагает, спросит, когда появится новый.
     */
    fun onDeclineOutbox() {
        val source = outbox ?: return
        val current = outboxItem.value ?: return
        if (outboxSending) return
        outboxItem.value = null
        sessionReplies.value += DayReply(today, current.copy(declined = true))
        viewModelScope.launch {
            try {
                source.decline(current.count)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "отказ от отправки не запомнился", error)
            }
        }
    }

    /** Ответ про отправку — разговор о сегодняшнем, в сегодняшней переписке. */
    private fun reply(text: String) {
        sessionReplies.value += DayReply(
            day = today,
            item = FeedItem.Reply(
                id = nextReplyId(),
                text = text,
                time = formatTime(nowMillis(), zone),
            ),
        )
    }

    // --- Телефон рядом: предложение записи -----------------------------------------------

    /** Предложение соседа в переписке — с кнопками ответа, как вопрос персонажа. */
    private fun PeerOfferEntity.toFeedItem(): FeedItem.Offer {
        val local = Instant.fromEpochMilliseconds(createdAt).toLocalDateTime(zone)
        return FeedItem.Offer(
            id = id,
            fromName = fromName,
            food = displayName,
            grams = grams,
            time = formatTime(createdAt, zone),
            minute = local.hour * 60 + local.minute,
            at = createdAt,
            choice = choice,
        )
    }

    /**
     * Ответ на предложение соседа.
     *
     * Сначала ответ, потом запись: пока `answer` не сказал, что строка и правда
     * ждала, писать нечего. Ноль — ответ уже дан, и второй тап не должен положить
     * вторую запись.
     */
    fun onAnswerOffer(id: Long, yes: Boolean) {
        val dao = peerOffers ?: return
        val before = dayKcal()
        val today = !isPast
        viewModelScope.launch {
            val offer = dao.findById(id) ?: return@launch
            if (dao.answer(id, if (yes) Choice.YES else Choice.NO) == 0) return@launch
            if (!yes) return@launch

            // Приём пищи — по своим часам: завтрак соседа и завтрак человека
            // приходятся на разное время, а поправить его можно и после.
            val meal = context().meal
            val entryId = diary.addSnapshot(
                displayName = offer.displayName,
                nutriments = Nutriments(
                    kcal100 = offer.kcal100,
                    prot100 = offer.prot100,
                    fat100 = offer.fat100,
                    carb100 = offer.carb100,
                    fiber100 = offer.fiber100,
                ),
                grams = offer.grams,
                meal = meal,
                date = shownDate.toEpochDays(),
                source = EntrySource.SHARED,
                now = entryTime(meal),
            )
            // Ссылку на продукт не кладём: на чужом телефоне она указывает на другую еду.
            reactToMeal(entryId, before, today)
        }
    }

    // --- Страница дневника в браузере ------------------------------------------------------

    /** Просьба браузера в переписке — с кнопками ответа, как вопрос персонажа. */
    private fun WebClientEntity.toFeedItem(): FeedItem.Browser {
        val local = Instant.fromEpochMilliseconds(createdAt).toLocalDateTime(zone)
        return FeedItem.Browser(
            id = id,
            label = label,
            code = code,
            time = formatTime(createdAt, zone),
            minute = local.hour * 60 + local.minute,
            at = createdAt,
            choice = choice,
        )
    }

    /**
     * Пустить браузер или нет. Ответ — один раз: второй тап базе отвечать уже не на что,
     * и проверка — в запросе, а не только в погасших кнопках.
     */
    fun onAnswerBrowser(id: Long, yes: Boolean) {
        val dao = browsers?.requests ?: return
        viewModelScope.launch { dao.answer(id, if (yes) Choice.YES else Choice.NO) }
    }

    /** Корзинка у отвеченной просьбы браузера: из переписки, а доступ браузера — каким был. */
    fun onHideBrowser(id: Long) {
        val dao = browsers?.requests ?: return
        viewModelScope.launch { dao.hide(id, clock.now().toEpochMilliseconds()) }
    }

    /** Корзинка у отвеченного предложения соседа. */
    fun onHideOffer(id: Long) {
        val dao = peerOffers ?: return
        viewModelScope.launch { dao.hide(id, clock.now().toEpochMilliseconds()) }
    }

    /**
     * Человек спросил адрес страницы — Зёрнышко называет его в ленте. Адреса нет —
     * телефон не в Wi-Fi, и Зёрнышко говорит, что сделать, а не «не нашлось».
     */
    private fun tellPageAddress(address: String?) {
        input.value = ""
        answer.value = null
        viewModelScope.launch {
            if (address != null) brain.answerWebAddress(address, clock.now()) else brain.answerNoWifi(clock.now())
        }
    }

    /**
     * Дальше человек пишет не себе, а соседу.
     *
     * Режим виден в подсказке строки ввода: невидимый однажды отправил бы чужую
     * еду в свой дневник.
     */
    fun onSendToPeer(id: String) {
        val source = peers ?: return
        viewModelScope.launch {
            sendingTo.value = source.nearby().first().find { it.id == id }
        }
    }

    /** Передумал: дальше снова пишет себе. */
    fun onCancelSendToPeer() {
        sendingTo.value = null
    }

    /**
     * Отправить еду соседу вместо записи себе.
     *
     * @return взяли ли на себя: `false` — режима нет, и запись идёт обычным путём
     */
    private fun sendToPeerInstead(candidate: FoodCandidate, grams: Int): Boolean {
        val source = peers ?: return false
        val person = sendingTo.value ?: return false
        // Режим снимается сразу: ждать ответа сети, держа человека в чужом
        // режиме, значило бы записать следующую еду не туда.
        sendingTo.value = null
        viewModelScope.launch {
            val sent = source.send(person.id, candidate.displayName, grams, candidate.nutriments)
            reply(
                if (sent) "${person.name} — отправлено: ${candidate.displayName}"
                else "${person.name} — не доставлено: телефон не отозвался"
            )
        }
        return true
    }

    // --- Персонажи: имя и разрешение писать ---------------------------------------------

    /** Сообщение персонажа в переписке: вопрос — с кнопками ответа, остальное — словами. */
    /** @param address адрес страницы сейчас: делятся живым адресом, а не тем, что был в тексте */
    private fun MascotMessageEntity.toFeedItem(address: String?): FeedItem.Message {
        val local = Instant.fromEpochMilliseconds(createdAt).toLocalDateTime(zone)
        val minute = local.hour * 60 + local.minute
        val time = formatTime(createdAt, zone)
        val asked = question
        return if (asked != null) {
            FeedItem.Ask(id, asked, text, time, minute, at = createdAt, choice = choice)
        } else {
            FeedItem.Said(
                id,
                mascot,
                text,
                time,
                minute,
                at = createdAt,
                share = address?.takeIf { topic == Topic.WEB_ADDRESS.name },
            )
        }
    }

    /**
     * Просьбы спросить разрешение у телефона.
     *
     * Событием, а не колбэком в конструктор: спрашивает экран, а экранная модель
     * переживает его пересоздание и держать его не должна. Ответ — [onNotificationsResult].
     */
    private val notificationAsks = Channel<Unit>(Channel.BUFFERED)
    val notificationRequests: Flow<Unit> = notificationAsks.receiveAsFlow()

    fun onAskName() {
        overlay.value = Overlay.Name
    }

    /** Имя названо, или `null` — «Не скажу». Второй раз не спрашивают ни в том, ни в другом случае. */
    fun onAnswerName(name: String?) {
        if (overlay.value == Overlay.Name) overlay.value = Overlay.None
        viewModelScope.launch { brain.answerName(name?.let(::cleanName), clock.now()) }
    }

    /**
     * «Пишите» или «Не надо». На «Пишите» сначала спрашивается телефон — один раз:
     * на отвеченный вопрос второй ответ не принимается, и повторный тап ничего не запускает.
     */
    fun onAnswerWrite(allow: Boolean) {
        viewModelScope.launch {
            if (brain.answerWrite(allow, clock.now()) && allow) notificationAsks.send(Unit)
        }
    }

    fun onNotificationsResult(granted: Boolean) {
        viewModelScope.launch { brain.notificationsResult(granted, clock.now()) }
    }

    /**
     * Просьбы подключить беговую дорожку: на «Подключить» Зёрнышку и на слово «дорожка». Выбирает её
     * телефон — системным окном, — поэтому событием, как разрешение на уведомления. Ответ — [onTreadmillResult].
     */
    private val treadmillAsks = Channel<Unit>(Channel.BUFFERED)
    val treadmillRequests: Flow<Unit> = treadmillAsks.receiveAsFlow()

    /** «Подключить» или «Не надо» про дорожку; повторный тап ничего не запускает. */
    fun onAnswerTreadmill(yes: Boolean) {
        viewModelScope.launch {
            if (brain.answerTreadmill(yes, clock.now()) && yes) treadmillAsks.send(Unit)
        }
    }

    /** Просьбы дать читать шагомер телефона: на «Считать» Зёрнышку и на слово «шаги». Ответ — [onStepsResult]. */
    private val stepsAsks = Channel<Unit>(Channel.BUFFERED)
    val stepsRequests: Flow<Unit> = stepsAsks.receiveAsFlow()

    /** «Считать» или «Не надо» про шаги телефона; повторный тап ничего не запускает. */
    fun onAnswerSteps(yes: Boolean) {
        viewModelScope.launch {
            if (brain.answerSteps(yes, clock.now()) && yes) stepsAsks.send(Unit)
        }
    }

    /** Телефон дал читать шаги — или нет. */
    fun onStepsResult(granted: Boolean) {
        viewModelScope.launch { brain.stepsResult(granted, clock.now()) }
    }

    /** Корзинка у сообщения персонажа: из переписки — в приложении и на странице, — но не из памяти. */
    fun onHideMessage(id: Long) {
        viewModelScope.launch { brain.hide(id, clock.now()) }
    }

    /** Телефон выбрал дорожку и получил Bluetooth — или нет. */
    fun onTreadmillResult(connected: Boolean) {
        viewModelScope.launch { brain.treadmillResult(connected, clock.now()) }
    }

    /** Пол из помощника цели: с ним персонажи говорят «ты ел» и «ты ела» правильно. */
    fun onSex(sex: Sex) {
        viewModelScope.launch { brain.setSex(sex) }
    }

    /** Рост из помощника цели — запоминается вместе с сохранённой целью, как пол. */
    fun onHeight(heightCm: Int) {
        viewModelScope.launch { brain.setHeight(heightCm) }
    }

    /** Тап по плашке шагов: шит с целью шагов и ростом — с тем, что уже задано. */
    fun onOpenSteps() {
        viewModelScope.launch {
            val profile = brain.profile.first()
            overlay.value = Overlay.Steps(goal = profile.stepsGoalOrDefault, heightCm = profile.heightCm, treadmill = treadmillSeen.first())
        }
    }

    /** @param heightCm рост; `null` — не назвали, остаётся прежний */
    fun onSetSteps(goal: Int, heightCm: Int?) {
        overlay.value = Overlay.None
        viewModelScope.launch { brain.setSteps(goal, heightCm) }
    }

    fun onSetGoal(kcal: Int, prot: Int, fat: Int, carb: Int) {
        overlay.value = Overlay.None
        viewModelScope.launch {
            // Цель версионируется датой, а не перезаписывается: смена цели сегодня
            // не должна задним числом переписать прогресс прошлых дней.
            diary.setGoal(DailyGoalEntity(today, kcal, prot, fat, carb))
        }
    }

    /**
     * Вес из строки ввода — единственная точка ввода веса на экране.
     *
     * Отдельного диалога больше нет, и это не упрощение ради упрощения: поле,
     * в котором человек и так пишет «овсянка 200», прекрасно принимает «вес 82,4»,
     * а кнопка в шапке существовала только затем, чтобы открыть форму с одним полем.
     */
    fun onLogWeight() {
        val grams = parseWeight(input.value) ?: return
        input.value = ""
        answer.value = null

        // Вес пишется в открытый день: «вчера утром было 82,4» — обычное дело.
        val day = shownDay
        viewModelScope.launch {
            personal.logWeight(day, grams, nowMillis())
            sessionReplies.value += DayReply(
                day = day,
                item = FeedItem.WeightLogged(
                    id = nextReplyId(),
                    grams = grams,
                    time = formatTime(nowMillis(), zone),
                ),
            )
            recompute(shownEntries)
        }
    }

    /**
     * Ответ на «Обед был?» — тап по чипсу: еда ложится в тот приём, о котором спрашивали.
     *
     * Вопрос не пропадает: остаётся в переписке с отмеченным выбором и погасшими чипсами.
     * Повторный тап по отвеченному ничего не записывает.
     */
    fun onPickGap(item: FeedItem.Gap, candidate: FoodCandidate) {
        val day = today
        val answered = sessionReplies.value.any { reply ->
            reply.day == day && (reply.item as? FeedItem.Gap)?.gap?.meal == item.gap.meal
        }
        if (item.picked != null || answered) return
        sessionReplies.value += DayReply(day, item.copy(picked = candidate))
        onPickPrediction(candidate, item.gap.meal)
    }

    fun onDismissMealGap(meal: MealType) {
        viewModelScope.launch {
            personal.dismissMealGap(today, meal)
            recompute(shownEntries)
        }
    }

    /**
     * Контекст моделей: открытый день, приём и час.
     *
     * У прошлого дня час — не «сейчас», а время выбранного приёма: подсказки
     * к вчерашнему ужину должны быть ужинными, а не утренними.
     */
    private fun context(prevRefKey: String? = null): PersonalContext {
        val meal = mealForNow()
        return PersonalContext(
            dateEpochDay = shownDay,
            hourOfDay = if (isPast) PAST_MEAL_HOURS.getValue(meal) else hourNow(),
            meal = meal,
            prevRefKey = prevRefKey,
        )
    }

    /** Когда записана еда: сегодня — сейчас, в прошлом дне — время его приёма. */
    private fun entryTime(meal: MealType): Long =
        if (isPast) pastEntryTime(shownDate, meal, shownEntries, zone) else nowMillis()

    /**
     * Приём пищи по времени суток и по тому, что уже записано.
     *
     * Правило целиком живёт в [mealForHour]: его же применяет группировка ленты,
     * и разойтись они не вправе — иначе подпись «новое добавится сюда» окажется
     * под пузырьком, в который запись не попадёт.
     */
    private fun mealForNow(): MealType =
        if (isPast) {
            // У прошлого дня нет «сейчас»: приём выбирает человек, а по умолчанию —
            // первый незаписанный, за ним в прошлый день обычно и возвращаются.
            pickedMeal.value ?: defaultPastMeal(shownEntries)
        } else {
            mealForHour(
                hour = hourNow(),
                lastEntry = shownEntries.lastOrNull(),
                nowMillis = nowMillis(),
            )
        }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val OUTBOX_SETTLE_MS = 3_000L
        const val TAG = "DiaryViewModel"

        /** Модель промолчала или упала — ответ всё равно должен быть, иначе многоточие висит вечно. */
        const val NO_ANSWER = "Не получилось ответить: нейросеть занята или ещё скачивается. Спроси через минуту."

        /** Когда у товара не указана порция: сто грамм — то, к чему привязан сам КБЖУ. */
        const val DEFAULT_PORTION_G = 100
    }
}

/**
 * Вес, дописанный к отсканированному названию.
 *
 * Скан знает товар, но не знает порцию, и подставленная нами сотня грамм —
 * догадка. Число в конце строки догадкой уже не является: его набрал человек,
 * глядя на упаковку, — поэтому и помечается как названное им.
 *
 * Процент в конце («Творог 5%») весом не считается: единицы там нет, а само
 * число часть названия.
 */
private fun ResolvedItem.withTypedGrams(input: String): ResolvedItem {
    // Ищем только в дописанном хвосте: «Кола 0,5» в названии товара — не 5 грамм.
    val typed = input.removePrefix(sourceText)
    val grams = TRAILING_GRAMS.find(typed.trim())
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: return this
    return copy(grams = grams, gramsGuessed = false)
}

private val TRAILING_GRAMS = Regex("""(\d{1,4})\s*(?:г|гр|грамм\w*|мл)?$""", RegexOption.IGNORE_CASE)

/**
 * «сколько я съел вчера?» — вопрос к дневнику, а не блюдо.
 *
 * Признак один — знак вопроса в конце. Угадывать вопрос по словам «сколько»
 * и «что» нельзя: «что-то сладкое» — тоже еда.
 */
internal fun isQuestion(text: String): Boolean = text.trim().let { it.length > 1 && it.endsWith('?') }

/** Последний съеденный продукт — вход модели переходов. */
private fun List<DiaryEntryEntity>.lastRefKey(): String? =
    lastOrNull()?.foodRef?.let(::parseFoodRef)?.serialize()

/**
 * «вес 82,4», «вес 82.4», «82.4 кг» — взвешивание, а не еда.
 *
 * Разбирается до поиска, потому что иначе «82.4 кг» уходит в резолвер как блюдо
 * весом 82 килограмма. Границы обязательны: без них опечатка в весе пишет
 * в историю величину, которую фильтр Калмана будет расхлёбывать неделю.
 *
 * @return вес в граммах или `null`, если это не вес
 */
internal fun parseWeight(text: String): Int? {
    val match = WEIGHT_PATTERN.matchEntire(text.trim()) ?: return null
    val number = match.groupValues.drop(1).firstOrNull(String::isNotEmpty) ?: return null
    val kg = number.replace(',', '.').toDoubleOrNull() ?: return null
    val grams = (kg * 1000).roundToInt()
    return grams.takeIf { it in MIN_WEIGHT_G..MAX_WEIGHT_G }
}

private val WEIGHT_PATTERN = Regex(
    """(?:вес\s+(\d{1,3}(?:[.,]\d{1,2})?)|(\d{1,3}(?:[.,]\d{1,2})?)\s*кг)""",
    RegexOption.IGNORE_CASE,
)

/** Ниже — не человек, выше — не человек. Обе границы существуют только против опечаток. */
private const val MIN_WEIGHT_G = 20_000
private const val MAX_WEIGHT_G = 400_000
