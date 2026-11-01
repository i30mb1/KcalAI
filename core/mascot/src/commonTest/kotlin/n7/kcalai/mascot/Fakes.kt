package n7.kcalai.mascot

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import n7.kcalai.database.BodyMetricDao
import n7.kcalai.database.BodyMetricEntity
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryDao
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.GoalDao
import n7.kcalai.database.MascotDao
import n7.kcalai.database.MascotMessageEntity
import n7.kcalai.database.MascotProfileEntity
import n7.kcalai.database.MealGapDismissEntity
import n7.kcalai.database.PersonalDao
import n7.kcalai.database.PortionSampleEntity
import n7.kcalai.database.RankerStateEntity
import n7.kcalai.database.RankerWeightEntity
import n7.kcalai.database.SuggestionEventEntity
import n7.kcalai.model.MealType

/** Дневник в памяти: тот же порядок, что у Room, — день, время, id. */
internal class FakeDiaryDao : DiaryDao {
    val entries = MutableStateFlow<List<DiaryEntryEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(entry: DiaryEntryEntity): Long {
        val id = if (entry.id != 0L) entry.id else nextId++
        nextId = maxOf(nextId, id + 1)
        entries.value += entry.copy(id = id)
        return id
    }

    override fun observeDay(dateEpochDay: Long): Flow<List<DiaryEntryEntity>> =
        entries.map { all -> all.filter { it.dateEpochDay == dateEpochDay }.sortedWith(ORDER) }

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
    val goals = MutableStateFlow<Map<Long, DailyGoalEntity>>(emptyMap())

    override suspend fun upsert(goal: DailyGoalEntity) {
        goals.value += goal.fromDateEpochDay to goal
    }

    override suspend fun goalFor(dateEpochDay: Long): DailyGoalEntity? =
        goals.value.values.filter { it.fromDateEpochDay <= dateEpochDay }.maxByOrNull { it.fromDateEpochDay }

    override fun observeGoalFor(dateEpochDay: Long): Flow<DailyGoalEntity?> =
        goals.map { all -> all.values.filter { it.fromDateEpochDay <= dateEpochDay }.maxByOrNull { it.fromDateEpochDay } }

    override fun observeAll(): Flow<List<DailyGoalEntity>> = goals.map { all -> all.values.sortedBy { it.fromDateEpochDay } }
}

internal class FakeBodyMetricDao : BodyMetricDao {
    override suspend fun upsert(metric: BodyMetricEntity) = Unit
    override suspend fun since(fromEpochDay: Long): List<BodyMetricEntity> = emptyList()
}

internal class FakePersonalDao : PersonalDao {
    private val dismissed = mutableSetOf<MealGapDismissEntity>()

    override suspend fun insertEvent(event: SuggestionEventEntity): Long = 1
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

/** Сообщения и профиль в памяти. */
internal class FakeMascotDao : MascotDao {
    val messages = MutableStateFlow<List<MascotMessageEntity>>(emptyList())
    val profileRow = MutableStateFlow<MascotProfileEntity?>(null)
    private var nextId = 1L

    override suspend fun insert(message: MascotMessageEntity): Long {
        val id = nextId++
        messages.value += message.copy(id = id)
        return id
    }

    override fun observeDay(dateEpochDay: Long): Flow<List<MascotMessageEntity>> =
        messages.map { all -> all.filter { it.dateEpochDay == dateEpochDay }.sortedWith(compareBy({ it.createdAt }, { it.id })) }

    override suspend fun recent(limit: Int): List<MascotMessageEntity> =
        messages.value.sortedWith(compareByDescending<MascotMessageEntity> { it.createdAt }.thenByDescending { it.id }).take(limit)

    override suspend fun findById(id: Long): MascotMessageEntity? = messages.value.find { it.id == id }

    override suspend fun markOpened(id: Long, at: Long) {
        messages.value = messages.value.map { if (it.id == id && it.openedAt == null) it.copy(openedAt = at) else it }
    }

    override suspend fun markAllOpened(at: Long) {
        messages.value = messages.value.map { if (it.openedAt == null) it.copy(openedAt = at) else it }
    }

    override fun observeProfile(): Flow<MascotProfileEntity?> = profileRow

    override suspend fun profile(): MascotProfileEntity? = profileRow.value

    override suspend fun upsertProfile(profile: MascotProfileEntity) {
        profileRow.value = profile
    }
}
