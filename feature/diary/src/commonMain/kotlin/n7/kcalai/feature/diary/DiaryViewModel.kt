package n7.kcalai.feature.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.feature.scanner.ScanStep
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.Log
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.Nutriments
import n7.kcalai.model.parseFoodRef
import n7.kcalai.model.serialize
import n7.kcalai.personal.ConfirmedPick
import n7.kcalai.personal.DayOutline
import n7.kcalai.personal.DayPlan
import n7.kcalai.personal.MealGap
import n7.kcalai.personal.PersonalContext
import n7.kcalai.personal.PersonalRepository
import n7.kcalai.personal.PortionObservation
import n7.kcalai.personal.ShownCandidate
import n7.kcalai.personal.TdeeEstimate
import n7.kcalai.personal.mealForHour
import n7.kcalai.repositories.DaySummary
import n7.kcalai.repositories.DayTotals
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.repositories.FoodRepository
import n7.kcalai.repositories.LabelReading
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.resolver.TextFoodResolver
import n7.kcalai.model.Sex
import n7.kcalai.mascot.asksToWrite
import n7.kcalai.mascot.MascotBrain
import n7.kcalai.database.MascotProfileEntity
import n7.kcalai.database.MascotMessageEntity
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlin.time.Instant

/** Поле шита цели, в котором должен стоять курсор при открытии. */
enum class GoalField { KCAL, PROT, FAT, CARB }

/** Что сейчас поверх экрана. Шиты и диалоги взаимоисключающие, поэтому это одно состояние. */
sealed interface Overlay {
    data object None : Overlay

    /** Цель на день. [field] — куда человек ткнул: остаток или конкретная плитка. */
    data class Goal(val field: GoalField = GoalField.KCAL) : Overlay

    /** Правка записи: вес, приём пищи, удаление. Открывается тапом по чипсу в пузырьке. */
    data class EditEntry(val entry: DiaryEntryEntity) : Overlay

    /** Камера: от штрих-кода до карточки нового продукта — одно окно, см. [ScanStep]. */
    data class Scan(val step: ScanStep = ScanStep.Barcode) : Overlay

    /**
     * Съёмка этикетки вхолостую — чтобы посмотреть, что вообще читается.
     *
     * Обычный путь к распознаванию идёт через промах штрих-кода, и проверить
     * разбор на конкретной пачке значит каждый раз найти товар, которого нет
     * в базе. Здесь то же распознавание запускается сразу и показывает себя
     * целиком: строки, маршрут разбора, что принято за ноль. В дневник отсюда
     * не попадает ничего.
     *
     * Точка входа — долгое нажатие на кнопку камеры: кнопок в шапке экран
     * больше не носит, а отладке отдельный жест дешевле отдельной кнопки.
     */
    data object LabelDebug : Overlay

    /** «Как тебя зовут?» — ответ на вопрос Яйца. */
    data object Name : Overlay
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
    val totals: NutrimentTotals = NutrimentTotals.ZERO,
    /** Калории по дням за неделю, последний элемент — сегодня. Всегда семь элементов. */
    val week: List<DaySummary> = emptyList(),
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
    /** Нужен шиту цели: расход по факту подставляется в калории одним тапом. */
    val tdee: TdeeEstimate? = null,
    /** Нужен шиту цели: помощь с целью начинается с уже записанного веса. */
    val lastWeightGrams: Int? = null,
    val overlay: Overlay = Overlay.None,
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
    private val clock: Clock = Clock.System,
    /** Зона, в которой считаются день и час: `createdAt` хранится в UTC. */
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    private fun hourNow(): Int = clock.now().toLocalDateTime(zone).hour


    private val input = MutableStateFlow("")
    private val answer = MutableStateFlow<Answer?>(null)
    private val scanned = MutableStateFlow<ResolvedItem?>(null)
    private val overlay = MutableStateFlow<Overlay>(Overlay.None)
    private val personalState = MutableStateFlow(PersonalState())

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
     * Реплики уходят из ленты («Удалил…» — после «Вернуть»), и номер по размеру
     * списка повторил бы ключ реплики, которая всё ещё стоит: `LazyColumn`
     * на повторном ключе падает.
     */
    private var replyCount = 0

    private fun nextReplyId(): Int = replyCount++

    /** Что ждёт отправки. `null` — пусто или сервера нет; пузырька нет. */
    private val outboxItem = MutableStateFlow<FeedItem.Outbox?>(null)

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
            combine(
                diary.observeDay(day.toEpochDays()),
                diary.observeGoal(day.toEpochDays()),
                // Неделя — всегда последние семь дней до сегодня: по ней и выбирают день.
                diary.observeWeek(now.toEpochDays()),
                diary.observeGoal(now.toEpochDays()),
                // Что персонажи написали в этот день — из шторки или ответом в ленте.
                brain.messages(day.toEpochDays()),
            ) { dayTotals, goal, week, todayGoal, said -> DayData(day, now, dayTotals, goal, week, todayGoal, said) }
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

    private val typing = combine(input, answer, scanned, overlay, ::TypingState)

    /** Вложенный combine: у типизированного [combine] потолок в пять потоков, а их больше. */
    private val typingAndReplies = combine(typing, sessionReplies, outboxItem, pickedMeal) { typed, replies, pending, meal ->
        Session(typed, replies, pending, meal)
    }

    val state = combine(typingAndReplies, data, personalState, brain.profile) { session, loaded, models, profile ->
        val (typed, replies, pending, meal) = session
        if (loaded == null) {
            return@combine DiaryUiState(input = typed.input, overlay = typed.overlay)
        }
        val day = loaded.date.toEpochDays()
        val pastMeal = if (loaded.date < loaded.today) meal ?: defaultPastMeal(loaded.day.entries) else null
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
                profile = profile,
            ),
            composer = composerRow(typed, models, pastMeal),
            totals = loaded.day.totals,
            week = loaded.week,
            date = loaded.date,
            today = loaded.today,
            goal = loaded.goal,
            todayGoal = loaded.todayGoal,
            tdee = models.tdee,
            lastWeightGrams = models.lastWeightGrams,
            overlay = typed.overlay,
            pastMeal = pastMeal,
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

    private class TypingState(
        val input: String,
        val answer: Answer?,
        val scanned: ResolvedItem?,
        val overlay: Overlay,
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
                    if (text.isBlank() || parseWeight(text) != null || text.continuesScan() || text.none(Char::isLetter)) {
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
                .collect { answer.value = it }
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
     * @param pastMeal приём, в который пишется прошлый день; `null` — открыт сегодняшний
     */
    private fun buildFeed(
        loaded: DayData,
        models: PersonalState,
        typed: TypingState,
        replies: List<FeedItem>,
        pending: FeedItem.Outbox?,
        pastMeal: MealType?,
        profile: MascotProfileEntity,
    ): List<FeedItem> {
        val now = nowMillis()
        val hour = hourNow()
        val isToday = pastMeal == null
        val dayTotals = loaded.day
        val goal = loaded.goal
        val feed = mutableListOf<FeedItem>(FeedItem.DaySeparator(dayLabel(loaded.date, loaded.today)))

        if (dayTotals.entries.isEmpty()) {
            feed += if (isToday) {
                daySummary(goal?.kcal?.takeIf { it > 0 }, loaded.week, models.dayOutline, hour)
            } else {
                FeedItem.EmptyDay(formatDayFull(loaded.date))
            }
        }

        val bubbles = groupIntoBubbles(dayTotals.entries, zone, now, activeMeal = pastMeal)
        val body = interleave(bubbles, loaded.said.map { it.toFeedItem() }, zone).toMutableList()
        models.mealGap?.takeIf { isToday }?.let { gap ->
            body.add(gapPosition(body, gap, zone), FeedItem.Gap(gap))
        }
        feed += body
        feed += replies
        pending?.let { feed += it }
        // Вопрос — последняя реплика: на него отвечают сейчас, а не листая вверх.
        if (isToday) ask(profile, dayTotals.entries.size, now)?.let { feed += FeedItem.Ask(it) }

        // Перебор вытесняет остаток, а не дополняет его: это один и тот же ответ
        // на один и тот же вопрос, и показывать оба значило бы спорить с собой.
        val target = goal?.kcal?.takeIf { it > 0 }
        if (target != null && dayTotals.totals.kcal > target) {
            feed += FeedItem.Over(dayTotals.totals.kcal - target, past = !isToday)
        } else if (isToday) {
            models.dayPlan?.let { feed += FeedItem.Remaining(it, nextMealTitle(hour)) }
        }

        typed.notFound?.let { feed += FeedItem.NotFound(it.query.trim()) }

        return feed
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

        val shown = answer.value?.items.orEmpty()
        val pickedIndex = shown.indexOfFirst { it.candidate?.ref == candidate.ref }
        val query = input.value

        input.value = ""
        answer.value = null

        viewModelScope.launch {
            val context = context()
            val target = meal ?: context.meal
            diary.add(
                candidate = candidate,
                grams = item.grams,
                meal = target,
                date = context.dateEpochDay,
                source = item.source,
                now = entryTime(target),
            )
            if (pickedIndex >= 0) {
                learn(query, shown, pickedIndex, item, candidate, context)
            }
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

        viewModelScope.launch {
            val context = context()
            val target = meal ?: context.meal
            diary.add(
                candidate = candidate,
                grams = grams,
                meal = target,
                date = context.dateEpochDay,
                source = EntrySource.MANUAL,
                now = entryTime(target),
            )
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
            val candidate = try {
                food.byBarcode(gtin)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Сюда входит и отсутствие сети. Промах и сбой ведут в одну и ту же
                // форму, и различать их перед человеком незачем.
                Log.e(TAG, "поиск по коду $gtin не удался", error)
                null
            }

            // Окно могли закрыть посреди поиска — тогда промах его не открывает.
            val stillWaiting = overlay.value == searching

            if (candidate == null) {
                // Промах ведёт прямо в карточку продукта в том же окне: код уже
                // известен, остальное человек снимет камерой или впишет сам.
                if (stillWaiting) overlay.value = Overlay.Scan(ScanStep.NewProduct(gtin))
            } else {
                if (stillWaiting) overlay.value = Overlay.None
                // Название уезжает в поле ввода, курсор — за ним. Скан опознал
                // товар, но не знает, сколько его съели, и дописать «150» к уже
                // готовой строке быстрее, чем открывать отдельный шит веса.
                // Чипс при этом остаётся и считает калории от набранного.
                scanned.value = candidate.toScannedItem(EntrySource.BARCODE)
                input.value = "${candidate.displayName} "
            }
        }
    }

    /** Тап по отсканированному. Ранжирующая модель об этом не узнаёт — см. [ComposerRow.Scanned]. */
    fun onPickScanned(item: ResolvedItem) {
        val candidate = item.candidate ?: return
        if (item.grams <= 0) return

        scanned.value = null
        input.value = ""
        viewModelScope.launch {
            val context = context()
            diary.add(
                candidate = candidate,
                grams = item.grams,
                meal = context.meal,
                date = context.dateEpochDay,
                source = item.source,
                now = entryTime(context.meal),
            )
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
     * Съёмка этикетки как самостоятельный способ добавить еду.
     *
     * У развесного, домашнего, вскрытой упаковки и товара, которого нет ни в одной
     * базе, кода нет вовсе, а таблица пищевой ценности есть. Заставлять человека
     * сначала сканировать несуществующий код, чтобы добраться до камеры, незачем.
     */
    fun onOpenLabelScan() {
        overlay.value = Overlay.Scan(ScanStep.NewProduct())
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
     * Реплика снимается сразу, до записи в базу: второй тап по той же кнопке
     * вставил бы запись с тем же ключом ещё раз.
     */
    fun onUndoDelete(item: FeedItem.Deleted) {
        if (sessionReplies.value.none { it.item == item }) return
        sessionReplies.value = sessionReplies.value.filterNot { it.item == item }

        viewModelScope.launch {
            try {
                diary.restore(item.entry)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "не удалось вернуть запись ${item.entry.id}", error)
            }
        }
    }

    /**
     * ✓ на клавиатуре — то же, что тап по чипсу, когда чипс один и выбирать
     * не из чего: вес из строки и отсканированный товар с дописанным весом.
     *
     * Выдачу поиска ✓ не трогает. Там выбор между вариантами, и делать его
     * за человека значило бы добавлять первое попавшееся совпадение вслепую.
     *
     * @return `true`, если что-то записано; иначе ✓ просто прячет клавиатуру
     */
    fun onSubmit(): Boolean {
        val text = input.value
        val scan = scanned.value
        return when {
            scan != null -> {
                onPickScanned(scan.withTypedGrams(text))
                true
            }
            parseWeight(text) != null -> {
                onLogWeight()
                true
            }
            else -> false
        }
    }

    // --- Цель и вес -------------------------------------------------------------------

    fun onOpenGoal(field: GoalField = GoalField.KCAL) {
        overlay.value = Overlay.Goal(field)
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
                count.takeUnless { it.isEmpty || !source.reachable() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "очередь отправки не посчиталась", error)
                null
            }
            // Идущую отправку не перебиваем: её итог придёт ответом в ленте.
            if (outboxItem.value?.sending == true) return@launch
            outboxItem.value = shown?.let { FeedItem.Outbox(it, sending = false) }
        }
    }

    /** Последний пересчёт очереди: новый отменяет прошлый, ждущий ответа сервера. */
    private var outboxRefresh: Job? = null

    /** Тап по «Отправить»: та же отправка, что у воркеров, только с ответом в ленте. */
    fun onSendOutbox() {
        val source = outbox ?: return
        val current = outboxItem.value ?: return
        if (current.sending) return
        outboxItem.value = current.copy(sending = true)

        viewModelScope.launch {
            val result = try {
                source.send()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "отправка не удалась", error)
                OutboxResult.Unreachable
            }
            reply(
                when {
                    // Фоновая отправка могла успеть раньше кнопки — это не ошибка.
                    result is OutboxResult.Sent && result.scans + result.products == 0 ->
                        "Всё уже ушло на сервер"
                    result is OutboxResult.Sent ->
                        "Отправлено на сервер: ${describeOutbox(result.scans, result.products)}"
                    else -> "Сервер не ответил — отправлю позже сам"
                }
            )
            outboxItem.value = null
            refreshOutbox()
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

    // --- Персонажи: имя и разрешение писать ---------------------------------------------

    /**
     * О чём спросить человека.
     *
     * Не с порога: имя — после первой записи дня, когда человек уже начал.
     * Разрешение писать — после имени и второй записи: сначала знакомство,
     * потом просьба. «Не надо» переспрашивается через месяц, см. [asksToWrite].
     */
    private fun ask(profile: MascotProfileEntity, entries: Int, now: Long): AskKind? = when {
        entries == 0 -> null
        profile.name == null && !profile.nameAsked -> AskKind.NAME
        entries >= 2 && profile.asksToWrite(Instant.fromEpochMilliseconds(now)) -> AskKind.WRITE
        else -> null
    }

    private fun MascotMessageEntity.toFeedItem(): FeedItem.Said {
        val local = Instant.fromEpochMilliseconds(createdAt).toLocalDateTime(zone)
        return FeedItem.Said(id, mascot, text, formatTime(createdAt, zone), local.hour * 60 + local.minute)
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

    /** «Пишите» или «Не надо». На «Пишите» сначала спрашивается телефон. */
    fun onAnswerWrite(allow: Boolean) {
        viewModelScope.launch {
            brain.answerWrite(allow, clock.now())
            if (allow) notificationAsks.send(Unit)
        }
    }

    fun onNotificationsResult(granted: Boolean) {
        viewModelScope.launch { brain.notificationsResult(granted, clock.now()) }
    }

    /** Пол из помощника цели: с ним персонажи говорят «ты ел» и «ты ела» правильно. */
    fun onSex(sex: Sex) {
        viewModelScope.launch { brain.setSex(sex) }
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
