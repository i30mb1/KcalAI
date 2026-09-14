package n7.kcalai.feature.diary

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
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
import n7.kcalai.repositories.DaySummary
import n7.kcalai.repositories.DayTotals
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.repositories.FoodRepository
import n7.kcalai.repositories.LabelReading
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.resolver.TextFoodResolver

/** Поле шита цели, в котором должен стоять курсор при открытии. */
enum class GoalField { KCAL, PROT, FAT, CARB }

/** Что сейчас поверх экрана. Шиты и диалоги взаимоисключающие, поэтому это одно состояние. */
sealed interface Overlay {
    data object None : Overlay

    /** Цель на день. [field] — куда человек ткнул: остаток или конкретная плитка. */
    data class Goal(val field: GoalField = GoalField.KCAL) : Overlay

    /** Правка записи: вес, приём пищи, удаление. Открывается тапом по чипсу в пузырьке. */
    data class EditEntry(val entry: DiaryEntryEntity) : Overlay

    /** Камера. */
    data object Scan : Overlay

    /**
     * Карточка нового продукта поверх камеры.
     *
     * Единственный путь завести продукт — и для промаха штрих-кода, и для
     * развесного, домашнего, вскрытой пачки. Раньше их было два: форма на пять
     * полей и сканер, после которого она открывалась. Промах скана — исход
     * основной, а не аварийный (российских товаров в Open Food Facts порядка
     * тридцати шести тысяч), и вести к нему через два экрана было незачем.
     *
     * @param gtin код, с промаха которого сюда пришли. `null` — этикетку снимают
     *        напрямую; экран попробует поймать код сам или примет его руками.
     */
    data class LabelScan(val gtin: String? = null) : Overlay

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
}

/** Что стоит над строкой ввода. Ряд ровно один — выбор между ними делает состояние. */
sealed interface ComposerRow {
    data object None : ComposerRow

    /** Набранное похоже на вес. Поиск не запускался вовсе. */
    data class Weight(val grams: Int) : ComposerRow

    /** Идея 1: что человек, скорее всего, съест сейчас. Поле пустое. */
    data class Predictions(val items: List<FoodCandidate>) : ComposerRow

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
    val date: LocalDate = LocalDate.now(),
    val goal: DailyGoalEntity? = null,
    /** Нужен шиту цели: расход по факту подставляется в калории одним тапом. */
    val tdee: TdeeEstimate? = null,
    val overlay: Overlay = Overlay.None,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DiaryViewModel(
    private val diary: DiaryRepository,
    private val resolver: TextFoodResolver,
    private val personal: PersonalRepository,
    private val food: FoodRepository,
    /**
     * Пнуть очередь отправки после того, как человек завёл продукт.
     *
     * Колбэк, а не WorkManager напрямую: планировщик — деталь сборки приложения,
     * и тащить его в модуль экрана ради одного вызова незачем.
     */
    private val onContributionQueued: () -> Unit = {},
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val input = MutableStateFlow("")
    private val suggestions = MutableStateFlow<List<ResolvedItem>>(emptyList())
    private val scanned = MutableStateFlow<ResolvedItem?>(null)
    private val searching = MutableStateFlow(false)
    private val overlay = MutableStateFlow<Overlay>(Overlay.None)
    private val personalState = MutableStateFlow(PersonalState())

    /**
     * Реплики, которые сказало приложение в этой сессии.
     *
     * Не в базе: «Записал 82,4 кг» — это ответ на действие, а не запись дневника,
     * и возвращать его при каждом открытии экрана значило бы копить в ленте
     * отчёты о прошлых разговорах.
     */
    private val sessionReplies = MutableStateFlow<List<FeedItem>>(emptyList())

    /**
     * День берётся в локальной зоне, а не из UTC-инстанта: иначе при перелёте
     * запись уезжает в соседние сутки.
     */
    private val today: Long get() = LocalDate.now(clock).toEpochDay()

    private val day = diary.observeDay(today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayTotals.EMPTY)

    private val goal = diary.observeGoal(today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val week = diary.observeWeek(today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val typing = combine(input, suggestions, scanned, searching, overlay, ::TypingState)

    /** Вложенный combine: у типизированного [combine] потолок в пять потоков, а их шесть. */
    private val typingAndReplies = combine(typing, sessionReplies) { typed, replies -> typed to replies }

    val state = combine(typingAndReplies, day, goal, personalState, week) {
        (typed, replies), dayTotals, dailyGoal, models, days ->
        DiaryUiState(
            input = typed.input,
            feed = buildFeed(dayTotals, dailyGoal, days, models, typed, replies),
            composer = composerRow(typed, models),
            totals = dayTotals.totals,
            week = days,
            date = LocalDate.now(clock),
            goal = dailyGoal,
            tdee = models.tdee,
            overlay = typed.overlay,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryUiState())

    private class TypingState(
        val input: String,
        val suggestions: List<ResolvedItem>,
        val scanned: ResolvedItem?,
        val searching: Boolean,
        val overlay: Overlay,
    ) {
        /** Вес распознаётся до поиска: «вес 82,4» — это не блюдо. */
        val weightGrams: Int? = parseWeight(input)

        /** Есть что набрать, но ничего не нашлось — это состояние надо показать, а не молчать. */
        val nothingFound: Boolean
            get() = input.isNotBlank() && weightGrams == null && !searching && suggestions.isEmpty()
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
                    if (text.isBlank() || parseWeight(text) != null || text.continuesScan()) {
                        emptyList()
                    } else {
                        searching.value = true
                        try {
                            resolver.suggest(text, context())
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            // Поиск идёт на каждый ввод. Сбой — это пустой список
                            // предложений, а не падение посреди набора.
                            Log.e(TAG, "поиск «$text» не удался", error)
                            emptyList()
                        } finally {
                            searching.value = false
                        }
                    }
                }
                .collect { suggestions.value = it }
        }

        // Модели пересчитываются от любого изменения дня или цели. Ставить пересчёт
        // в каждый обработчик по отдельности означало бы рано или поздно забыть один
        // из них и показывать вчерашние предсказания.
        viewModelScope.launch {
            combine(day, goal) { dayTotals, dailyGoal -> dayTotals to dailyGoal }
                .collect { (dayTotals, _) -> recompute(dayTotals.entries) }
        }
    }

    // --- Сборка ленты -----------------------------------------------------------------

    /**
     * Лента целиком.
     *
     * Порядок реплик — порядок разговора: сначала то, что записал человек, потом
     * то, что на это отвечает приложение. Поэтому остаток и перебор стоят в конце,
     * а сводка дня — в начале и только пока записей нет.
     */
    private fun buildFeed(
        dayTotals: DayTotals,
        goal: DailyGoalEntity?,
        week: List<DaySummary>,
        models: PersonalState,
        typed: TypingState,
        replies: List<FeedItem>,
    ): List<FeedItem> {
        val zone = clock.zone
        val now = clock.millis()
        val hour = LocalTime.now(clock).hour
        val feed = mutableListOf<FeedItem>(FeedItem.DaySeparator)

        if (dayTotals.entries.isEmpty()) {
            feed += summary(goal, week, models.dayOutline, hour)
        }

        val bubbles = groupIntoBubbles(dayTotals.entries, zone, now)
        val body = bubbles.toMutableList<FeedItem>()
        models.mealGap?.let { gap ->
            body.add(gapPosition(bubbles, gap, zone), FeedItem.Gap(gap))
        }
        feed += body
        feed += replies

        // Перебор вытесняет остаток, а не дополняет его: это один и тот же ответ
        // на один и тот же вопрос, и показывать оба значило бы спорить с собой.
        val target = goal?.kcal?.takeIf { it > 0 }
        if (target != null && dayTotals.totals.kcal > target) {
            feed += FeedItem.Over(dayTotals.totals.kcal - target)
        } else {
            models.dayPlan?.let { feed += FeedItem.Remaining(it, nextMealTitle(hour)) }
        }

        if (typed.nothingFound) feed += FeedItem.NotFound(typed.input.trim())

        return feed
    }

    /**
     * Сводка дня.
     *
     * Первая фраза есть всегда, остальные — только если история их подтверждает.
     * Пустая сводка честнее выдуманной: «обычно вы завтракаете в 8:00» на второй
     * день использования это ложь, которую человек сразу заметит.
     */
    private fun summary(
        goal: DailyGoalEntity?,
        week: List<DaySummary>,
        outline: DayOutline?,
        hour: Int,
    ): FeedItem.Summary {
        val target = goal?.kcal?.takeIf { it > 0 }
        val head = if (target != null) {
            "${greeting(hour)} Сегодня можно $target ккал"
        } else {
            "Сегодня без цели — задайте её тапом по остатку"
        }

        // Сегодня входит в счёт: цель на него ещё не нарушена, и это правда.
        // Неделя здесь — именно последние семь дней, а не шесть прошедших.
        val withGoal = week.filter { it.goalKcal != null }
        val weekLine = if (withGoal.isEmpty()) {
            null
        } else {
            val hit = withGoal.count { it.kcal <= (it.goalKcal ?: 0) }
            "За неделю вы уложились в цель $hit ${daysWord(hit)} из ${week.size}"
        }

        return FeedItem.Summary(
            greeting = head,
            weekLine = weekLine,
            outline = outline,
        )
    }

    /**
     * Ряд над строкой ввода.
     *
     * Порядок проверок — порядок приоритета: вес важнее поиска, потому что он
     * уже однозначен; скан важнее предсказаний, потому что человек только что
     * навёл камеру.
     */
    private fun composerRow(typed: TypingState, models: PersonalState): ComposerRow = when {
        // Скан идёт первым: пока в строке стоит опознанный товар, всё остальное
        // в этом ряду было бы предложением заменить точное совпадение догадкой.
        typed.scanned != null -> ComposerRow.Scanned(typed.scanned.withTypedGrams(typed.input))
        typed.weightGrams != null -> ComposerRow.Weight(typed.weightGrams)
        typed.suggestions.isNotEmpty() -> ComposerRow.Results(typed.suggestions)
        typed.input.isBlank() && models.predictions.isNotEmpty() ->
            ComposerRow.Predictions(models.predictions)
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
            personalState.value = PersonalState(
                predictions = personal.predictions(context),
                mealGap = personal.mealGap(context, entries),
                dayPlan = personal.remainingPlan(context, entries),
                tdee = personal.tdee(context.dateEpochDay),
                dayOutline = personal.dayOutline(context),
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
        if (text.isBlank()) suggestions.value = emptyList()

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

        val shown = suggestions.value
        val pickedIndex = shown.indexOfFirst { it.candidate?.ref == candidate.ref }
        val query = input.value

        input.value = ""
        suggestions.value = emptyList()

        viewModelScope.launch {
            val context = context()
            diary.add(
                candidate = candidate,
                grams = item.grams,
                meal = meal ?: context.meal,
                date = context.dateEpochDay,
                source = item.source,
                now = clock.millis(),
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
            diary.add(
                candidate = candidate,
                grams = grams,
                meal = meal ?: context.meal,
                date = context.dateEpochDay,
                source = EntrySource.MANUAL,
                now = clock.millis(),
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
                now = clock.millis(),
            )
        )
    }

    // --- Скан штрих-кода --------------------------------------------------------------

    fun onOpenScan() {
        overlay.value = Overlay.Scan
    }

    /**
     * Код распознан — дальше цепочка: свои продукты, справочник, кэш, сеть.
     *
     * Найденное показывается чипсом и ждёт тапа, а не падает в дневник само.
     * Скан опознаёт товар, но не знает, сколько его съели, и молча записать
     * целую пачку было бы хуже, чем не записать ничего.
     */
    fun onScanned(gtin: String) {
        overlay.value = Overlay.None
        scanned.value = null

        viewModelScope.launch {
            searching.value = true
            val candidate = try {
                food.byBarcode(gtin)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Сюда входит и отсутствие сети. Промах и сбой ведут в одну и ту же
                // форму, и различать их перед человеком незачем.
                Log.e(TAG, "поиск по коду $gtin не удался", error)
                null
            } finally {
                searching.value = false
            }

            if (candidate == null) {
                // Промах ведёт прямо в карточку продукта: код уже известен,
                // остальное человек снимет камерой или наберёт сам.
                overlay.value = Overlay.LabelScan(gtin)
            } else {
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
                now = clock.millis(),
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
    fun onSaveNewProduct(gtin: String?, name: String, nutriments: Nutriments, servingG: Int?) {
        overlay.value = Overlay.None

        viewModelScope.launch {
            val candidate = try {
                food.saveOwnProduct(gtin, name, nutriments, servingG, clock.millis())
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
        overlay.value = Overlay.LabelScan()
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
            if (grams != entry.grams) personal.onGramsEdited(entry.id, grams, clock.millis())
            if (meal != entry.meal) diary.moveToMeal(entry.id, meal)
        }
    }

    fun onDeleteEntry(id: Long) {
        overlay.value = Overlay.None
        viewModelScope.launch { diary.delete(id) }
    }

    // --- Цель и вес -------------------------------------------------------------------

    fun onOpenGoal(field: GoalField = GoalField.KCAL) {
        overlay.value = Overlay.Goal(field)
    }

    fun onDismissOverlay() {
        overlay.value = Overlay.None
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
        suggestions.value = emptyList()

        viewModelScope.launch {
            personal.logWeight(today, grams, clock.millis())
            sessionReplies.value += FeedItem.WeightLogged(
                id = sessionReplies.value.size,
                grams = grams,
                time = formatTime(clock.millis(), clock.zone),
            )
            recompute(day.value.entries)
        }
    }

    fun onDismissMealGap(meal: MealType) {
        viewModelScope.launch {
            personal.dismissMealGap(today, meal)
            recompute(day.value.entries)
        }
    }

    private fun context(prevRefKey: String? = null) = PersonalContext(
        dateEpochDay = today,
        hourOfDay = LocalTime.now(clock).hour,
        meal = mealForNow(),
        prevRefKey = prevRefKey,
    )

    /**
     * Приём пищи по времени суток и по тому, что уже записано.
     *
     * Правило целиком живёт в [mealForHour]: его же применяет группировка ленты,
     * и разойтись они не вправе — иначе подпись «новое добавится сюда» окажется
     * под пузырьком, в который запись не попадёт.
     */
    private fun mealForNow(): MealType = mealForHour(
        hour = LocalTime.now(clock).hour,
        lastEntry = day.value.entries.lastOrNull(),
        nowMillis = clock.millis(),
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val TAG = "DiaryViewModel"

        /** Когда у товара не указана порция: сто грамм — то, к чему привязан сам КБЖУ. */
        const val DEFAULT_PORTION_G = 100
    }

    class Factory(
        private val diary: DiaryRepository,
        private val resolver: TextFoodResolver,
        private val personal: PersonalRepository,
        private val food: FoodRepository,
        private val onContributionQueued: () -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DiaryViewModel(diary, resolver, personal, food, onContributionQueued) as T
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
    val grams = TRAILING_GRAMS.find(input.trim())
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
