package n7.kcalai.mascot

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import n7.kcalai.database.DiaryDao
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.GoalDao
import n7.kcalai.database.MascotProfileEntity
import n7.kcalai.database.totals
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.MealType
import n7.kcalai.model.parseFoodRef
import n7.kcalai.model.serialize
import n7.kcalai.personal.DayOutline
import n7.kcalai.personal.FoodHistory
import n7.kcalai.personal.PersonalContext
import n7.kcalai.personal.PersonalRepository
import n7.kcalai.personal.mealForHour

/**
 * Когда человек бодрствует — по его привычкам.
 *
 * @param wakeHour с этого часа можно писать
 * @param sleepHour с этого часа уже нельзя
 */
data class Rhythm(val wakeHour: Int, val sleepHour: Int) {
    companion object {
        /** Пока привычек нет: обычный день. */
        val DEFAULT = Rhythm(wakeHour = 8, sleepHour = 22)
    }
}

/** Еда, которую персонаж предложит записать одним нажатием, и в какой приём. */
data class Suggestion(val candidate: FoodCandidate, val meal: MealType)

/** Факты, ритм и что предложить записать — по теме сообщения. */
data class Snapshot(val facts: Facts, val rhythm: Rhythm, val suggestions: Map<Topic, Suggestion>)

/**
 * Факты дня из дневника и моделей персонализации.
 *
 * Ничего не выдумывает: серия, пропуск, остаток, любимое — всё посчитано
 * по записям человека тем же кодом, что подсказывает еду в строке ввода.
 */
class FactsBuilder(
    private val diaryDao: DiaryDao,
    private val goalDao: GoalDao,
    private val personal: PersonalRepository,
    private val zone: TimeZone,
) {

    suspend fun build(now: Instant, profile: MascotProfileEntity?): Snapshot {
        val local = now.toLocalDateTime(zone)
        val today = local.date.toEpochDays()
        val nowMillis = now.toEpochMilliseconds()

        // Дневник мог измениться мимо экрана — из шторки, в фоне: счётчики моделей
        // пересчитываются заново, а не берутся из кэша экранной модели.
        personal.invalidate()

        val entries = diaryDao.since(today - HISTORY_DAYS + 1)
        val todayEntries = entries.filter { it.dateEpochDay == today }
        val last = entries.maxByOrNull { it.createdAt }
        val days = entries.mapTo(HashSet()) { it.dateEpochDay }
        val history = FoodHistory.from(entries, today, zone)

        val context = PersonalContext(
            dateEpochDay = today,
            hourOfDay = local.hour,
            meal = mealForHour(local.hour, todayEntries.lastOrNull(), nowMillis),
            prevRefKey = todayEntries.lastOrNull()?.foodRef?.let(::parseFoodRef)?.serialize(),
        )
        val outline = personal.dayOutline(context)
        val gap = personal.mealGap(context, todayEntries)
        val idea = personal.remainingPlan(context, todayEntries)
            ?.options?.firstOrNull()
            ?.items?.maxByOrNull { it.nutriments.prot100 }
        val next = personal.predictions(context).firstOrNull()

        val suggestions = buildMap {
            gap?.suggestions?.firstOrNull()?.let { put(Topic.MISSED_MEAL, Suggestion(it, gap.meal)) }
            idea?.let { put(Topic.PROTEIN_LEFT, Suggestion(it, context.meal)) }
            next?.let { put(Topic.LONG_GAP, Suggestion(it, context.meal)) }
        }

        val facts = Facts(
            now = local,
            name = profile?.name,
            sex = profile?.sex,
            daysTogether = entries.minOfOrNull { it.dateEpochDay }?.let { (today - it).toInt() } ?: 0,
            minutesSinceLastEntry = last?.let { ((nowMillis - it.createdAt) / MINUTE_MS).toInt() }?.takeIf { it >= 0 },
            lastFood = last?.displayName,
            lastEntryAt = last?.let { Instant.fromEpochMilliseconds(it.createdAt).toLocalDateTime(zone).time },
            todayEntries = todayEntries.size,
            missed = gap?.let {
                val usual = it.suggestions.firstOrNull()
                MissedMeal(it.meal, it.typicalHour, usual?.displayName, usual?.servingG)
            },
            protLeftG = proteinLeft(today, todayEntries),
            protIdea = idea?.displayName,
            streakDays = generateSequence(today - 1) { it - 1 }.takeWhile { it in days }.count(),
            daysAway = last?.let { (today - it.dateEpochDay).toInt() } ?: 0,
            favorite = history.items.values.filter { it.count >= FAVORITE_MIN }.maxByOrNull { it.count }?.displayName,
            yesterdayOnGoal = onGoal(today - 1, entries),
            chronotype = outline?.chronotype(),
            absentMinutes = profile?.lastOpenAt?.let { ((nowMillis - it) / MINUTE_MS).toInt() }?.takeIf { it >= 0 },
        )
        return Snapshot(facts, outline?.rhythm() ?: Rhythm.DEFAULT, suggestions)
    }

    /** Сколько белка осталось, граммы. Только при цели по белку и только когда день начат. */
    private suspend fun proteinLeft(today: Long, todayEntries: List<DiaryEntryEntity>): Int? {
        if (todayEntries.isEmpty()) return null
        val goal = goalDao.goalFor(today)?.prot?.takeIf { it > 0 } ?: return null
        val eaten = todayEntries.sumOf { it.totals().protCg } / CENTIGRAMS_PER_GRAM
        return (goal - eaten).takeIf { it > 0 }
    }

    /** Калории дня в пределах ±10% цели. Пустой день в цель не попадает: «не записал» — не успех. */
    private suspend fun onGoal(day: Long, entries: List<DiaryEntryEntity>): Boolean {
        val goal = goalDao.goalFor(day)?.kcal?.takeIf { it > 0 } ?: return false
        val kcal = entries.filter { it.dateEpochDay == day }.sumOf { it.totals().kcal }
        if (kcal == 0) return false
        return kcal >= goal * (1 - GOAL_TOLERANCE) && kcal <= goal * (1 + GOAL_TOLERANCE)
    }

    private companion object {
        /** Та же глубина, что у моделей персонализации. */
        const val HISTORY_DAYS = 90
        const val MINUTE_MS = 60_000L
        const val CENTIGRAMS_PER_GRAM = 100
        const val FAVORITE_MIN = 3
        const val GOAL_TOLERANCE = 0.10
    }
}

/** Просыпается за час до первой обычной еды, засыпает через три часа после последней. */
private fun DayOutline.rhythm(): Rhythm = Rhythm(
    wakeHour = (meals.first().hour - 1).coerceIn(6, 10),
    sleepHour = (meals.last().hour + 3).coerceIn(21, 23),
)

private fun DayOutline.chronotype(): Chronotype? = when {
    meals.first().hour <= 7 -> Chronotype.LARK
    meals.last().hour >= 21 -> Chronotype.OWL
    else -> null
}
