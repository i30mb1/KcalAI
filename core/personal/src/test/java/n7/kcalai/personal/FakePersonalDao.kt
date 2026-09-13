package n7.kcalai.personal

import n7.kcalai.database.MealGapDismissEntity
import n7.kcalai.database.PersonalDao
import n7.kcalai.database.PortionSampleEntity
import n7.kcalai.database.RankerStateEntity
import n7.kcalai.database.RankerWeightEntity
import n7.kcalai.database.SuggestionEventEntity
import n7.kcalai.model.MealType

/**
 * Хранилище персонализации в памяти.
 *
 * Room in-memory потребовал бы инструментального прогона на устройстве; поведение,
 * которое проверяется в этих тестах, целиком лежит в моделях, а не в SQL.
 */
internal class FakePersonalDao : PersonalDao {

    private val events = mutableListOf<SuggestionEventEntity>()
    private val samples = mutableListOf<PortionSampleEntity>()
    private val weights = mutableMapOf<String, Double>()
    private val dismissed = mutableListOf<MealGapDismissEntity>()
    private var state: RankerStateEntity? = null
    private var nextId = 1L

    override suspend fun insertEvent(event: SuggestionEventEntity): Long {
        val id = nextId++
        events += event.copy(id = id)
        return id
    }

    override suspend fun eventsAfter(afterId: Long, limit: Int): List<SuggestionEventEntity> =
        events.filter { it.id > afterId }.sortedBy { it.id }.take(limit)

    override suspend fun insertPortionSample(sample: PortionSampleEntity) {
        samples += sample.copy(id = nextId++)
    }

    /** Как в SQL: сначала самые свежие, потом уже срез. */
    override suspend fun portionSamples(refKey: String, limit: Int): List<PortionSampleEntity> =
        samples.filter { it.refKey == refKey }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override suspend fun trimPortionSamples(refKey: String, keep: Int) {
        val survivors = samples.filter { it.refKey == refKey }
            .sortedByDescending { it.createdAt }
            .take(keep)
            .toSet()
        samples.removeAll { it.refKey == refKey && it !in survivors }
    }

    override suspend fun rankerWeights(): List<RankerWeightEntity> =
        weights.map { (name, value) -> RankerWeightEntity(name, value) }

    override suspend fun upsertRankerWeights(weights: List<RankerWeightEntity>) {
        weights.forEach { this.weights[it.name] = it.value }
    }

    override suspend fun rankerState(): RankerStateEntity? = state

    override suspend fun upsertRankerState(state: RankerStateEntity) {
        this.state = state
    }

    override suspend fun dismissMealGap(dismiss: MealGapDismissEntity) {
        if (dismiss !in dismissed) dismissed += dismiss
    }

    override suspend fun dismissedMeals(dateEpochDay: Long): List<MealType> =
        dismissed.filter { it.dateEpochDay == dateEpochDay }.map { it.meal }

    override suspend fun trimDismissed(beforeEpochDay: Long) {
        dismissed.removeAll { it.dateEpochDay < beforeEpochDay }
    }
}
