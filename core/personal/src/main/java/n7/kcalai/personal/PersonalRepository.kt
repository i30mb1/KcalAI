package n7.kcalai.personal

import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import n7.kcalai.database.BodyMetricDao
import n7.kcalai.database.BodyMetricEntity
import n7.kcalai.database.DiaryDao
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.GoalDao
import n7.kcalai.database.MealGapDismissEntity
import n7.kcalai.database.PersonalDao
import n7.kcalai.database.RankerStateEntity
import n7.kcalai.database.RankerWeightEntity
import n7.kcalai.database.SuggestionEventEntity
import n7.kcalai.database.totals
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.MealType
import n7.kcalai.model.parseFoodRef
import n7.kcalai.model.serialize

/** Показанный кандидат: ровно те его свойства, которые видит ранжирующая модель. */
data class ShownCandidate(val ref: FoodRef, val exactMatch: Boolean)

/** Подтверждённая человеком граммовка одной единицы продукта. */
data class PortionObservation(val ref: FoodRef, val unit: String, val gramsPerUnit: Int)

/**
 * Всё, что произошло в момент подтверждения.
 *
 * Один объект, а не пять параметров: этот момент — единственный источник разметки
 * в приложении, и терять его части по дороге нельзя.
 */
data class ConfirmedPick(
    val context: PersonalContext,
    val query: String,
    /** В порядке показа. */
    val shown: List<ShownCandidate>,
    val pickedIndex: Int,
    /** `null`, если вес не был назван человеком — свою же догадку запоминать нельзя. */
    val portion: PortionObservation?,
    val now: Long,
)

/**
 * Персонализация целиком: шесть моделей и их общее состояние.
 *
 * Все шесть работают над одним и тем же — над личной историей, — поэтому история
 * строится один раз и кэшируется, а не пересчитывается каждой моделью заново.
 */
class PersonalRepository(
    private val diaryDao: DiaryDao,
    private val personalDao: PersonalDao,
    private val bodyMetricDao: BodyMetricDao,
    private val goalDao: GoalDao,
    private val zone: ZoneId,
) : Personalization {

    private val mutex = Mutex()
    private var cache: Cache? = null
    private var cachedWeights: DoubleArray? = null

    private class Cache(val day: Long, val entries: List<DiaryEntryEntity>, val history: FoodHistory)

    /** Дневник изменился — счётчики устарели. Вызывается после любой записи или удаления. */
    suspend fun invalidate() = mutex.withLock { cache = null }

    private suspend fun history(today: Long): FoodHistory = cached(today).history

    private suspend fun cached(today: Long): Cache = mutex.withLock {
        cache?.takeIf { it.day == today }?.let { return it }
        val entries = diaryDao.since(today - HISTORY_DAYS + 1)
        Cache(today, entries, FoodHistory.from(entries, today, zone)).also { cache = it }
    }

    // --- Идея 1: предсказание следующего продукта -------------------------------------

    suspend fun predictions(context: PersonalContext): List<FoodCandidate> =
        NextFoodModel.predict(history(context.dateEpochDay), context)

    // --- Идея 2: ранжирование выдачи --------------------------------------------------

    override suspend fun rerank(
        candidates: List<FoodCandidate>,
        context: PersonalContext,
    ): List<FoodCandidate> =
        CandidateRanker.rerank(weights(), history(context.dateEpochDay), candidates, context)

    // --- Идея 3: личные граммовки -----------------------------------------------------

    private val portions = PortionMemory(personalDao)

    override suspend fun portionUnits(ref: FoodRef): Map<String, Int> = portions.unitsFor(ref)

    // --- Идея 4: пропущенный приём пищи -----------------------------------------------

    suspend fun mealGap(context: PersonalContext, todayEntries: List<DiaryEntryEntity>): MealGap? {
        personalDao.trimDismissed(context.dateEpochDay)
        return MealGapDetector.detect(
            history = history(context.dateEpochDay),
            mealsLoggedToday = todayEntries.mapTo(mutableSetOf()) { it.meal },
            dismissed = personalDao.dismissedMeals(context.dateEpochDay).toSet(),
            nowHour = context.hourOfDay,
            context = context,
        )
    }

    suspend fun dismissMealGap(dateEpochDay: Long, meal: MealType) {
        personalDao.dismissMealGap(MealGapDismissEntity(dateEpochDay, meal))
    }

    // --- Идея 5: добор остатка дня ----------------------------------------------------

    suspend fun remainingPlan(
        context: PersonalContext,
        todayEntries: List<DiaryEntryEntity>,
    ): DayPlan? {
        val goal = goalDao.goalFor(context.dateEpochDay) ?: return null
        if (todayEntries.isEmpty()) return null

        val eatenKcal = todayEntries.sumOf { it.totals().kcal }
        val eatenProtCg = todayEntries.sumOf { it.totals().protCg }

        return RemainingDayPlanner.plan(
            history = history(context.dateEpochDay),
            remainingKcal = goal.kcal - eatenKcal,
            remainingProtCg = goal.prot * CENTIGRAMS_PER_GRAM - eatenProtCg,
            eatenToday = todayEntries.mapNotNullTo(mutableSetOf()) { entry ->
                parseFoodRef(entry.foodRef)?.serialize()
            },
            context = context,
        )
    }

    // --- Идея 6: расход по факту ------------------------------------------------------

    suspend fun logWeight(dateEpochDay: Long, weightGrams: Int, now: Long) {
        bodyMetricDao.upsert(BodyMetricEntity(dateEpochDay, weightGrams, now))
    }

    suspend fun tdee(today: Long): TdeeEstimate? {
        val weighIns = bodyMetricDao.since(today - TdeeEstimator.WINDOW_DAYS + 1)
            .associate { it.dateEpochDay to it.weightGrams }
        if (weighIns.isEmpty()) return null

        val intake = cached(today).entries
            .groupBy { it.dateEpochDay }
            .mapValues { (_, entries) -> entries.sumOf { it.totals().kcal } }

        return TdeeEstimator.estimate(weighIns, intake, today)
    }

    // --- Обучение ---------------------------------------------------------------------

    /**
     * Человек подтвердил выбор — записываем событие и учимся на нём.
     *
     * Обучение идёт сразу, по данным в памяти, а не отложенным проходом по журналу:
     * признаки кандидата известны только здесь и сейчас, а восстанавливать их
     * задним числом значило бы учиться на приблизительной копии того, что человек видел.
     * Журнал при этом всё равно пишется — как запись, по которой модель можно
     * переобучить с нуля, если формула признаков изменится.
     */
    suspend fun onConfirmed(pick: ConfirmedPick) {
        val history = history(pick.context.dateEpochDay)
        val picked = pick.shown.getOrNull(pick.pickedIndex) ?: return

        val eventId = personalDao.insertEvent(
            SuggestionEventEntity(
                createdAt = pick.now,
                hourOfDay = pick.context.hourOfDay,
                meal = pick.context.meal,
                query = pick.query,
                shownRefs = encodeShown(pick.shown),
                pickedRef = picked.ref.serialize(),
                pickedIndex = pick.pickedIndex,
            )
        )

        val current = weights()
        CandidateRanker.train(
            weights = current,
            shown = pick.shown.mapIndexed { index, shownCandidate ->
                CandidateRanker.features(
                    history = history,
                    ref = shownCandidate.ref,
                    exactMatch = shownCandidate.exactMatch,
                    baseIndex = index,
                    context = pick.context,
                )
            },
            pickedIndex = pick.pickedIndex,
        )
        persistWeights(current, eventId)

        pick.portion?.let { portions.remember(it.ref, it.unit, it.gramsPerUnit, pick.now) }
        invalidate()
    }

    /**
     * Человек поправил вес уже добавленной записи — самый чистый сигнал, какой бывает.
     *
     * Здесь единица неизвестна, поэтому наблюдение записывается на типичную порцию:
     * «когда я говорю про этот продукт, речь обычно про столько грамм».
     */
    suspend fun onGramsEdited(entryId: Long, grams: Int, now: Long) {
        diaryDao.updateGrams(entryId, grams)
        val entry = diaryDao.findById(entryId)
        parseFoodRef(entry?.foodRef)?.let { ref ->
            portions.remember(ref, PortionMemory.PORTION_UNIT, grams, now)
        }
        invalidate()
    }

    // --- Веса модели ------------------------------------------------------------------

    private suspend fun weights(): DoubleArray =
        cachedWeights ?: loadWeights().also { cachedWeights = it }

    private suspend fun loadWeights(): DoubleArray {
        val stored = personalDao.rankerWeights().associate { it.name to it.value }
        // Отсутствующий вес читается как стартовый: добавление признака не обнуляет обучение.
        return DoubleArray(CandidateRanker.FEATURES.size) { index ->
            stored[CandidateRanker.FEATURES[index]] ?: CandidateRanker.INITIAL[index]
        }
    }

    private suspend fun persistWeights(values: DoubleArray, lastEventId: Long) {
        personalDao.upsertRankerWeights(
            CandidateRanker.FEATURES.mapIndexed { index, name ->
                RankerWeightEntity(name = name, value = values[index])
            }
        )
        val trained = (personalDao.rankerState()?.trainedEvents ?: 0) + 1
        personalDao.upsertRankerState(RankerStateEntity(lastEventId = lastEventId, trainedEvents = trained))
        cachedWeights = values
    }

    private companion object {
        /** Глубина истории. Дальше привычки человека уже не те. */
        const val HISTORY_DAYS = 90
        const val CENTIGRAMS_PER_GRAM = 100
    }
}

/**
 * Показанные кандидаты в строку журнала: `1:generic:12|0:barcode:4600…`
 *
 * Первый символ — совпал ли алиас целиком. Признак пришлось бы восстанавливать
 * поиском по базе, а база к тому времени может обновиться; хранить один символ дешевле.
 */
private fun encodeShown(shown: List<ShownCandidate>): String =
    shown.joinToString("|") { "${if (it.exactMatch) 1 else 0}:${it.ref.serialize()}" }
