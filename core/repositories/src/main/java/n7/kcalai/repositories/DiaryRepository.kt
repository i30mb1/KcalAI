package n7.kcalai.repositories

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryDao
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.GoalDao
import n7.kcalai.database.totals
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.plus
import n7.kcalai.model.serialize

/** Записи дня вместе с посчитанными итогами. */
data class DayTotals(
    val entries: List<DiaryEntryEntity>,
    val totals: NutrimentTotals,
) {
    companion object {
        val EMPTY = DayTotals(emptyList(), NutrimentTotals.ZERO)
    }
}

/** Что именно кладём в дневник: продукт, вес и чем он был распознан. */
data class DiaryAddition(
    val candidate: FoodCandidate,
    val grams: Int,
    val source: EntrySource,
)

class DiaryRepository(
    private val diaryDao: DiaryDao,
    private val goalDao: GoalDao,
) {

    /** Кладёт в дневник снимок КБЖУ, а не ссылку: обновление справочника не трогает историю. */
    suspend fun add(
        candidate: FoodCandidate,
        grams: Int,
        meal: MealType,
        date: Long,
        source: EntrySource,
        now: Long,
    ): Long = diaryDao.insert(candidate.toEntity(grams, meal, date, source, now))

    /**
     * Добавляет разом всё, что человек подтвердил одной фразой.
     *
     * `createdAt` разносится на миллисекунду, чтобы порядок в списке дня совпал
     * с порядком слов во фразе — сортировка идёт по `createdAt, id`.
     */
    suspend fun addAll(
        additions: List<DiaryAddition>,
        meal: MealType,
        date: Long,
        now: Long,
    ): List<Long> {
        if (additions.isEmpty()) return emptyList()
        return diaryDao.insertAll(
            additions.mapIndexed { index, addition ->
                addition.candidate.toEntity(
                    grams = addition.grams,
                    meal = meal,
                    date = date,
                    source = addition.source,
                    now = now + index,
                )
            }
        )
    }

    fun observeDay(date: Long): Flow<DayTotals> =
        diaryDao.observeDay(date).map { entries ->
            DayTotals(entries = entries, totals = entries.sumTotals())
        }

    suspend fun delete(id: Long) = diaryDao.delete(id)

    suspend fun goalFor(date: Long): DailyGoalEntity? = goalDao.goalFor(date)

    fun observeGoal(date: Long): Flow<DailyGoalEntity?> = goalDao.observeGoalFor(date)

    suspend fun setGoal(goal: DailyGoalEntity) = goalDao.upsert(goal)
}

private fun FoodCandidate.toEntity(
    grams: Int,
    meal: MealType,
    date: Long,
    source: EntrySource,
    now: Long,
): DiaryEntryEntity = DiaryEntryEntity(
    id = 0,
    dateEpochDay = date,
    meal = meal,
    displayName = displayName,
    grams = grams,
    kcal100 = nutriments.kcal100,
    prot100 = nutriments.prot100,
    fat100 = nutriments.fat100,
    carb100 = nutriments.carb100,
    source = source,
    foodRef = ref.serialize(),
    createdAt = now,
)

private fun List<DiaryEntryEntity>.sumTotals(): NutrimentTotals =
    fold(NutrimentTotals.ZERO) { acc, entry -> acc + entry.totals() }
