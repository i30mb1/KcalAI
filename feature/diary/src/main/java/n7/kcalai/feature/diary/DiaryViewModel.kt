package n7.kcalai.feature.diary

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
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
import n7.kcalai.model.ProductDraft
import n7.kcalai.model.parseFoodRef
import n7.kcalai.model.serialize
import n7.kcalai.personal.ConfirmedPick
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
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.resolver.TextFoodResolver

/** Что сейчас поверх экрана. Диалоги взаимоисключающие, поэтому это одно состояние, а не три флага. */
sealed interface Overlay {
    data object None : Overlay
    data object Goal : Overlay
    data object Weight : Overlay
    data class EditGrams(val entry: DiaryEntryEntity) : Overlay

    /** Камера. */
    data object Scan : Overlay

    /**
     * Промах скана — и это основной исход, а не сбой: российских товаров
     * в Open Food Facts порядка тридцати шести тысяч.
     *
     * [draft] сегодня всегда пуст. Поле существует ради v2, где таблицу с упаковки
     * прочитает OCR: подключить его тогда — это заполнить черновик, а не переделывать
     * форму. Стоит это сейчас одного параметра.
     */
    data class NewProduct(
        val gtin: String,
        val draft: ProductDraft = ProductDraft.EMPTY,
    ) : Overlay
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
)

data class DiaryUiState(
    val input: String = "",
    /** Чем может быть набранное блюдо. Каждое предложение — готовая к добавлению позиция. */
    val suggestions: List<ResolvedItem> = emptyList(),
    val searching: Boolean = false,
    /**
     * Товар, найденный по штрих-коду.
     *
     * Отдельно от [suggestions] намеренно, хотя рисуется тем же чипсом. Список
     * предложений — это выдача поиска, и тап по нему учит ранжирующую модель.
     * Скан выдачей не является: человек не выбирал из вариантов, и считать его тап
     * исправлением ранжирования значило бы учить модель на том, чего она не показывала.
     */
    val scanned: ResolvedItem? = null,
    val entries: List<DiaryEntryEntity> = emptyList(),
    val totals: NutrimentTotals = NutrimentTotals.ZERO,
    /** Калории по дням за неделю, последний элемент — сегодня. Всегда семь элементов. */
    val week: List<DaySummary> = emptyList(),
    val date: LocalDate = LocalDate.now(),
    val goal: DailyGoalEntity? = null,
    val personal: PersonalState = PersonalState(),
    val overlay: Overlay = Overlay.None,
) {
    /** Есть что набрать, но ничего не нашлось — это состояние надо показать, а не молчать. */
    val nothingFound: Boolean
        get() = input.isNotBlank() && !searching && suggestions.isEmpty()

    /** Предсказания уместны, только пока человек ничего не набрал и ничего не отсканировал. */
    val showPredictions: Boolean
        get() = input.isBlank() && scanned == null && personal.predictions.isNotEmpty()
}

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

    val state = combine(typing, day, goal, personalState, week) { typed, dayTotals, dailyGoal, models, days ->
        DiaryUiState(
            input = typed.input,
            suggestions = typed.suggestions,
            scanned = typed.scanned,
            searching = typed.searching,
            entries = dayTotals.entries,
            totals = dayTotals.totals,
            week = days,
            date = LocalDate.now(clock),
            goal = dailyGoal,
            personal = models,
            overlay = typed.overlay,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryUiState())

    private class TypingState(
        val input: String,
        val suggestions: List<ResolvedItem>,
        val scanned: ResolvedItem?,
        val searching: Boolean,
        val overlay: Overlay,
    )

    init {
        // Предложения пересобираются на паузу в наборе, а не на каждый символ:
        // поиск ходит в базу, и дёргать её на «г», «гр», «гре» незачем.
        viewModelScope.launch {
            input
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .mapLatest { text ->
                    if (text.isBlank()) {
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
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Персонализация — надстройка. Её отказ не должен уносить дневник.
            Log.e(TAG, "пересчёт моделей не удался", error)
            personalState.value = PersonalState()
        }
    }

    fun onInputChange(text: String) {
        input.value = text
        if (text.isBlank()) suggestions.value = emptyList()
        // Человек начал набирать — значит, к отсканированному он не вернётся.
        if (text.isNotBlank()) scanned.value = null
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
     * Добавление предсказанного продукта — идеи 1 и 4.
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
                overlay.value = Overlay.NewProduct(gtin)
            } else {
                scanned.value = candidate.toScannedItem(EntrySource.BARCODE)
            }
        }
    }

    /** Тап по отсканированному. Ранжирующая модель об этом не узнаёт — см. [DiaryUiState.scanned]. */
    fun onPickScanned(item: ResolvedItem) {
        val candidate = item.candidate ?: return
        if (item.grams <= 0) return

        scanned.value = null
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
     * Сохранение продукта, заведённого после промаха.
     *
     * Продукт оседает локально и находится мгновенно навсегда, а копия уходит
     * в очередь отправки — чтобы следующий человек с той же пачкой форму уже не видел.
     * Валидация цифр осталась в форме: сюда попадает только прошедшее её.
     */
    fun onSaveNewProduct(gtin: String, name: String, nutriments: Nutriments, servingG: Int?) {
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

            scanned.value = candidate.toScannedItem(EntrySource.MANUAL)
            onContributionQueued()
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
        gramsGuessed = servingG == null,
    )

    fun onDeleteEntry(id: Long) {
        viewModelScope.launch { diary.delete(id) }
    }

    // --- Правка веса записи: самый чистый сигнал для памяти порций --------------------

    fun onStartEditGrams(entry: DiaryEntryEntity) {
        overlay.value = Overlay.EditGrams(entry)
    }

    fun onEditGrams(entryId: Long, grams: Int) {
        overlay.value = Overlay.None
        if (grams <= 0) return
        viewModelScope.launch { personal.onGramsEdited(entryId, grams, clock.millis()) }
    }

    // --- Цель и вес -------------------------------------------------------------------

    fun onOpenGoal() {
        overlay.value = Overlay.Goal
    }

    fun onOpenWeight() {
        overlay.value = Overlay.Weight
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

    fun onLogWeight(weightGrams: Int) {
        overlay.value = Overlay.None
        if (weightGrams <= 0) return
        viewModelScope.launch {
            personal.logWeight(today, weightGrams, clock.millis())
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

    /** Приём пищи по времени суток: спрашивать об этом отдельно — лишний шаг. */
    private fun mealForNow(): MealType = when (LocalTime.now(clock).hour) {
        in 0..10 -> MealType.BREAKFAST
        in 11..15 -> MealType.LUNCH
        in 16..21 -> MealType.DINNER
        else -> MealType.SNACK
    }

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

/** Последний съеденный продукт — вход модели переходов. */
private fun List<DiaryEntryEntity>.lastRefKey(): String? =
    lastOrNull()?.foodRef?.let(::parseFoodRef)?.serialize()
