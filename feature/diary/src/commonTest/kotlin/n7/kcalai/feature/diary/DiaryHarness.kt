package n7.kcalai.feature.diary

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import n7.kcalai.database.BodyMetricDao
import n7.kcalai.database.BodyMetricEntity
import n7.kcalai.database.CachedProductDao
import n7.kcalai.database.CachedProductEntity
import n7.kcalai.database.ContributionDao
import n7.kcalai.database.ContributionEntity
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryDao
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.GoalDao
import n7.kcalai.database.MealGapDismissEntity
import n7.kcalai.database.PersonalDao
import n7.kcalai.database.PortionSampleEntity
import n7.kcalai.database.RankerStateEntity
import n7.kcalai.database.RankerWeightEntity
import n7.kcalai.database.SuggestionEventEntity
import n7.kcalai.database.UserFoodDao
import n7.kcalai.database.UserFoodEntity
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import n7.kcalai.personal.PersonalRepository
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.repositories.FoodDbSource
import n7.kcalai.repositories.FoodRepository
import n7.kcalai.repositories.RemoteProductSource
import n7.kcalai.resolver.TextFoodResolver

/**
 * Экранная модель дневника со всем окружением в памяти.
 *
 * Репозитории настоящие, подменено только хранилище: проверяется то, что видит
 * человек, — лента, строка ввода, записи дня, — а не то, в каком порядке модель
 * дёргает зависимости. Справочника нет вовсе: поиск по нему падает, и модель
 * обязана пережить это так же, как переживает сбой на устройстве.
 */
internal class DiaryHarness(now: String = "2026-10-31T09:00:00Z") {

    val clock = MutableClock(Instant.parse(now))
    val zone = TimeZone.UTC

    val diaryDao = FakeDiaryDao()
    val goalDao = FakeGoalDao()
    val bodyMetricDao = FakeBodyMetricDao()
    val userFoodDao = FakeUserFoodDao()

    val today: Long get() = clock.now().toLocalDateTime(zone).date.toEpochDays()

    fun viewModel(dispatcher: CoroutineDispatcher, outbox: DiaryOutbox? = null): DiaryViewModel {
        val personal = PersonalRepository(diaryDao, FakePersonalDao(), bodyMetricDao, goalDao, zone)
        val food = FoodRepository(
            foodDb = FoodDbSource { error("справочник в этих тестах не открывается") },
            userFoodDao = userFoodDao,
            cachedProductDao = FakeCachedProductDao(),
            contributionDao = FakeContributionDao(),
            remote = RemoteProductSource { null },
            dispatcher = dispatcher,
        )
        return DiaryViewModel(
            diary = DiaryRepository(diaryDao, goalDao),
            resolver = TextFoodResolver(food, personal),
            personal = personal,
            food = food,
            outbox = outbox,
            clock = clock,
            zone = zone,
        )
    }

    /**
     * Запись, сделанная раньше, чем открылся экран.
     *
     * @param daysAgo в какой день она лежит; время — тот же час, что сейчас, но в тот день
     */
    suspend fun addEntry(
        name: String,
        grams: Int,
        meal: MealType,
        minutesAgo: Int = 30,
        daysAgo: Int = 0,
        kcal100: Int = 110,
    ): DiaryEntryEntity {
        val entry = DiaryEntryEntity(
            dateEpochDay = today - daysAgo,
            meal = meal,
            displayName = name,
            grams = grams,
            kcal100 = kcal100,
            prot100 = 420,
            fat100 = 110,
            carb100 = 2_130,
            source = EntrySource.TEXT,
            foodRef = "generic:7",
            createdAt = clock.now().toEpochMilliseconds() - minutesAgo * 60_000L - daysAgo * DAY_MS,
        )
        val id = diaryDao.insert(entry)
        return entry.copy(id = id)
    }

    /** Продукт, который предложила бы модель: с порцией, чтобы его можно было записать тапом. */
    val kefir = FoodCandidate(
        ref = FoodRef.Generic(42),
        displayName = "Кефир 1%",
        nutriments = Nutriments(kcal100 = 40, prot100 = 300, fat100 = 100, carb100 = 400),
        servingG = 250,
    )

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

/** Очередь отправки, у которой сервер то отвечает, то нет. */
internal class FakeOutbox(var count: OutboxCount, var serverUp: Boolean) : DiaryOutbox {
    override suspend fun pending(): OutboxCount = count
    override suspend fun reachable(): Boolean = serverUp
    override suspend fun send(): OutboxResult = OutboxResult.Sent(count.scans, count.products)
}

internal class MutableClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}

/** Как Room с автоключом: ноль — выдать новый id, иначе вставить с переданным. */
internal class FakeDiaryDao : DiaryDao {

    private val entries = MutableStateFlow<List<DiaryEntryEntity>>(emptyList())
    private var nextId = 1L

    /** База ещё не ответила на запрос дня — как на холодном старте, пока она открывается. */
    private val dayGate = MutableStateFlow(true)

    fun holdDay() {
        dayGate.value = false
    }

    fun releaseDay() {
        dayGate.value = true
    }

    fun day(dateEpochDay: Long): List<DiaryEntryEntity> =
        entries.value.filter { it.dateEpochDay == dateEpochDay }.sortedWith(ORDER)

    override suspend fun insert(entry: DiaryEntryEntity): Long {
        val id = if (entry.id != 0L) entry.id else nextId++
        nextId = maxOf(nextId, id + 1)
        entries.value += entry.copy(id = id)
        return id
    }

    override fun observeDay(dateEpochDay: Long): Flow<List<DiaryEntryEntity>> =
        combine(dayGate, entries) { open, all -> open to all }
            .filter { (open, _) -> open }
            .map { (_, all) -> all.filter { it.dateEpochDay == dateEpochDay }.sortedWith(ORDER) }

    override suspend fun delete(id: Long) {
        entries.value = entries.value.filter { it.id != id }
    }

    override suspend fun since(fromEpochDay: Long): List<DiaryEntryEntity> =
        entries.value.filter { it.dateEpochDay >= fromEpochDay }.sortedWith(ORDER)

    override fun observeSince(fromEpochDay: Long): Flow<List<DiaryEntryEntity>> =
        entries.map { all -> all.filter { it.dateEpochDay >= fromEpochDay }.sortedWith(ORDER) }

    override suspend fun findById(id: Long): DiaryEntryEntity? = entries.value.find { it.id == id }

    override suspend fun updateGrams(id: Long, grams: Int) {
        entries.value = entries.value.map { if (it.id == id) it.copy(grams = grams) else it }
    }

    override suspend fun updateMeal(id: Long, meal: MealType) {
        entries.value = entries.value.map { if (it.id == id) it.copy(meal = meal) else it }
    }

    private companion object {
        val ORDER = compareBy<DiaryEntryEntity>({ it.dateEpochDay }, { it.createdAt }, { it.id })
    }
}

internal class FakeGoalDao : GoalDao {

    private val goals = MutableStateFlow<Map<Long, DailyGoalEntity>>(emptyMap())

    override suspend fun upsert(goal: DailyGoalEntity) {
        goals.value += goal.fromDateEpochDay to goal
    }

    override suspend fun goalFor(dateEpochDay: Long): DailyGoalEntity? = current(goals.value, dateEpochDay)

    override fun observeGoalFor(dateEpochDay: Long): Flow<DailyGoalEntity?> =
        goals.map { current(it, dateEpochDay) }

    override fun observeAll(): Flow<List<DailyGoalEntity>> =
        goals.map { all -> all.values.sortedBy { it.fromDateEpochDay } }

    private fun current(all: Map<Long, DailyGoalEntity>, day: Long): DailyGoalEntity? =
        all.values.filter { it.fromDateEpochDay <= day }.maxByOrNull { it.fromDateEpochDay }
}

internal class FakeBodyMetricDao : BodyMetricDao {

    val weights = mutableMapOf<Long, Int>()

    override suspend fun upsert(metric: BodyMetricEntity) {
        weights[metric.dateEpochDay] = metric.weightGrams
    }

    override suspend fun since(fromEpochDay: Long): List<BodyMetricEntity> =
        weights.filterKeys { it >= fromEpochDay }.map { (day, grams) -> BodyMetricEntity(day, grams, 0) }
}

internal class FakeUserFoodDao : UserFoodDao {

    private val foods = mutableListOf<UserFoodEntity>()

    fun add(barcode: String?, name: String, kcal100: Int): UserFoodEntity =
        UserFoodEntity(
            id = foods.size + 1L,
            barcode = barcode,
            name = name,
            kcal100 = kcal100,
            prot100 = 2_500,
            fat100 = 2_700,
            carb100 = 200,
            createdAt = 0,
        ).also { foods += it }

    override suspend fun insert(food: UserFoodEntity): Long {
        val stored = food.copy(id = foods.size + 1L)
        foods += stored
        return stored.id
    }

    override suspend fun update(food: UserFoodEntity) {
        foods.replaceAll { if (it.id == food.id) food else it }
    }

    override suspend fun findByBarcode(barcode: String): UserFoodEntity? = foods.find { it.barcode == barcode }

    override suspend fun findById(id: Long): UserFoodEntity? = foods.find { it.id == id }

    override suspend fun all(): List<UserFoodEntity> = foods.toList()
}

private class FakeCachedProductDao : CachedProductDao {
    private val products = mutableMapOf<String, CachedProductEntity>()

    override suspend fun insert(product: CachedProductEntity) {
        products[product.gtin] = product
    }

    override suspend fun findByGtin(gtin: String): CachedProductEntity? = products[gtin]
}

private class FakeContributionDao : ContributionDao {
    private val queue = mutableListOf<ContributionEntity>()

    override suspend fun insert(contribution: ContributionEntity): Long {
        queue += contribution.copy(id = queue.size + 1L)
        return queue.size.toLong()
    }

    override suspend fun pending(limit: Int): List<ContributionEntity> =
        queue.filter { it.sentAt == null }.take(limit)

    override suspend fun pendingCount(): Int = queue.count { it.sentAt == null }

    override suspend fun markSent(ids: List<Long>, sentAt: Long) {
        queue.replaceAll { if (it.id in ids) it.copy(sentAt = sentAt) else it }
    }
}

/** Персонализации здесь нечему учиться: журнал и веса копятся, но не проверяются. */
private class FakePersonalDao : PersonalDao {
    private val dismissed = mutableListOf<MealGapDismissEntity>()
    private var nextId = 1L

    override suspend fun insertEvent(event: SuggestionEventEntity): Long = nextId++
    override suspend fun insertPortionSample(sample: PortionSampleEntity) = Unit
    override suspend fun portionSamples(refKey: String, limit: Int): List<PortionSampleEntity> = emptyList()
    override suspend fun trimPortionSamples(refKey: String, keep: Int) = Unit
    override suspend fun rankerWeights(): List<RankerWeightEntity> = emptyList()
    override suspend fun upsertRankerWeights(weights: List<RankerWeightEntity>) = Unit
    override suspend fun rankerState(): RankerStateEntity? = null
    override suspend fun upsertRankerState(state: RankerStateEntity) = Unit

    override suspend fun dismissMealGap(dismiss: MealGapDismissEntity) {
        dismissed += dismiss
    }

    override suspend fun dismissedMeals(dateEpochDay: Long): List<MealType> =
        dismissed.filter { it.dateEpochDay == dateEpochDay }.map { it.meal }

    override suspend fun trimDismissed(beforeEpochDay: Long) {
        dismissed.removeAll { it.dateEpochDay < beforeEpochDay }
    }
}
