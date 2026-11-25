package n7.kcalai.webhost

import n7.kcalai.model.StepsSettings
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.fiber
import n7.kcalai.database.totals
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType
import n7.kcalai.model.PageGrace
import n7.kcalai.model.forGrams
import n7.kcalai.model.formatTime
import n7.kcalai.model.suggestedFiber
import n7.kcalai.model.owner
import n7.kcalai.personal.defaultPastMeal
import n7.kcalai.personal.mealForHour
import n7.kcalai.personal.pastEntryTime
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.repositories.StepsRepository
import n7.kcalai.repositories.WalkRepository
import n7.kcalai.model.TreadmillSample
import n7.kcalai.model.Walk
import n7.kcalai.webapi.WebSaid
import n7.kcalai.webapi.WebWalk
import kotlin.math.roundToInt
import kotlin.time.Instant
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.webapi.WebDaySummary
import n7.kcalai.webapi.WebEntry
import n7.kcalai.webapi.WebMacros
import n7.kcalai.webapi.WebSuggestion
import n7.kcalai.webapi.weekOf
import kotlin.time.Clock

/**
 * Дневник для страницы — тем же путём, что строка ввода в приложении.
 *
 * Поиск тот же ([suggestFood] — это поиск строки ввода), запись — тот же снимок КБЖУ,
 * приём пищи — по часам, как у записи с телефона, а правка граммов учит память порций
 * так же, как правка в шите ([regram]).
 *
 * Подсказки помнятся по запросу: страница называет запись номером в выдаче, и записать
 * надо ровно ту, что была у неё перед глазами, даже если поиск с тех пор выучил новое.
 *
 * @param regram правка граммов записи — та же, что из шита: `(id, граммы, сейчас)`
 * @param rename новое имя — туда же, куда его кладёт ответ Яйцу
 * @param keepPage сколько держать страницу, когда телефон убран, — в профиль
 * @param walks прогулки по дорожке, подключённой к телефону
 * @param steps шаги дня — телефона и дорожки вместе
 * @param live её живые числа, см. [n7.kcalai.repositories.TreadmillFeed]
 * @param walkSaid что Зёрнышко сказало о прогулках в ленте дня
 * @param hide скрыть реплику персонажа корзинкой — тем же путём, что в приложении
 * @param stepsGoal цель шагов на день и рост — из профиля телефона
 * @param saveSteps цель шагов и рост из панели шагов — в профиль; рост `null` — не трогать
 */
class RepositoryWebDiary(
    private val diary: DiaryRepository,
    private val walks: WalkRepository,
    private val steps: StepsRepository,
    private val walkSaid: (Long) -> Flow<List<WebSaid>>,
    override val live: Flow<TreadmillSample?>,
    private val hide: suspend (Long) -> Unit,
    private val suggestFood: suspend (String) -> List<ResolvedItem>,
    private val regram: suspend (entryId: Long, grams: Int, now: Long) -> Unit,
    private val rename: suspend (String) -> Unit,
    private val keepPage: suspend (PageGrace) -> Unit,
    private val stepsGoal: Flow<StepsSettings> = flowOf(StepsSettings()),
    private val saveSteps: suspend (goal: Int, heightCm: Int?) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : WebDiary {

    private val lock = Mutex()

    /** Последние выдачи по запросам, старые первыми. Браузеров бывает несколько, и запросы у них свои. */
    private val shown = LinkedHashMap<String, List<ResolvedItem>>()

    override val today: Long get() = clock.todayIn(zone).toEpochDays()

    override fun day(date: Long): Flow<WebDayContent> =
        combine(diary.observeDay(date), diary.observeGoal(date), walks.observeDay(date), counted(date), walkSaid(date)) { totals, goal, walked, (counted, target, seen), said ->
            WebDayContent(
                walks = walked.map { it.toWeb(date) },
                steps = counted.total,
                phoneSteps = counted.phone,
                stepsGoal = target.goal,
                heightCm = target.heightCm,
                treadmillSeen = seen,
                said = said,
                entries = totals.entries.map { it.toWeb(zone) },
                goalKcal = goal?.kcal?.takeIf { it > 0 },
                goalMacros = goal?.takeIf { it.prot > 0 && it.fat > 0 && it.carb > 0 }?.let { WebMacros(it.prot, it.fat, it.carb) },
                // Та же цель клетчатки, что в шапке приложения: своя — или по калориям цели.
                fiberGoal = goal?.fiber ?: suggestedFiber(goal?.kcal),
            )
        }

    /**
     * Шаги дня, цель к ним и была ли дорожка — одним потоком: у типизированного combine потолок в пять.
     * Дорожка «была», если с неё записана хоть одна прогулка.
     */
    private fun counted(date: Long) =
        combine(steps.observeDay(date), stepsGoal, walks.observeLast().map { it != null }) { counted, target, seen -> Triple(counted, target, seen) }

    override fun week(date: Long): Flow<List<WebDaySummary>> {
        val week = weekOf(date)
        return combine(diary.observeWeek(todayEpochDay = week.last, days = week.count()), steps.observeWeek(week.last)) { days, counted ->
            val stepsOf = counted.toMap()
            days.map { WebDaySummary(date = it.dateEpochDay, kcal = it.kcal, goalKcal = it.goalKcal, steps = stepsOf[it.dateEpochDay] ?: 0) }
        }
    }

    /** Минуты от полуночи дня [date] по часам телефона; прогулка за полночь кончается в конце дня. */
    private fun Walk.toWeb(date: Long): WebWalk {
        fun minuteOf(ms: Long): Int {
            val at = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
            return if (at.date.toEpochDays() > date) DAY_MINUTES else at.hour * MINUTES_PER_HOUR + at.minute
        }
        return WebWalk(minuteOf(startedAt), minuteOf(seenAt), distanceM, elapsedS, steps.roundToInt(), topSpeedKmh)
    }

    override suspend fun suggest(query: String): List<WebSuggestion> = found(query).map { item ->
        val candidate = checkNotNull(item.candidate)
        WebSuggestion(
            name = candidate.displayName,
            grams = item.grams,
            kcal = candidate.nutriments.forGrams(item.grams).kcal,
            mascot = candidate.nutriments.owner(),
        )
    }

    /**
     * Сегодня — приём по часам (или выбранный) и время «сейчас». Прошлый день — выбранный
     * приём или первый незаписанный, и его обычный час: ужин, дописанный утром, не должен
     * стать ужином в девять утра и научить этому модели привычек.
     */
    override suspend fun add(query: String, index: Int, grams: Int, day: Long, meal: MealType?) {
        val candidate = (lock.withLock { shown[query] } ?: found(query)).getOrNull(index)?.candidate ?: return
        val entries = diary.observeDay(day).first().entries
        val now = clock.now()
        val (target, at) = if (day >= today) {
            val target = meal ?: mealForHour(
                hour = now.toLocalDateTime(zone).hour,
                lastEntry = entries.maxByOrNull { it.createdAt },
                nowMillis = now.toEpochMilliseconds(),
            )
            target to now.toEpochMilliseconds()
        } else {
            val target = meal ?: defaultPastMeal(entries)
            target to pastEntryTime(LocalDate.fromEpochDays(day), target, entries, zone)
        }
        diary.add(candidate, grams, target, day.coerceAtMost(today), EntrySource.TEXT, at)
    }

    override suspend fun setGrams(entryId: Long, grams: Int) {
        if (diary.entry(entryId) != null) regram(entryId, grams, clock.now().toEpochMilliseconds())
    }

    override suspend fun delete(entryId: Long) = diary.delete(entryId)

    override suspend fun setGoal(goal: WebMacros) {
        diary.setGoal(DailyGoalEntity(fromDateEpochDay = today, kcal = goal.kcal, prot = goal.prot, fat = goal.fat, carb = goal.carb))
    }

    override suspend fun setName(name: String) = rename(name)

    override suspend fun setGrace(grace: PageGrace) = keepPage(grace)

    override suspend fun setSteps(goal: Int, heightCm: Int?) = saveSteps(goal, heightCm)

    override suspend fun hideSaid(messageId: Long) = hide(messageId)

    /** Опознанное, с весом, — и запомненное под этим запросом. */
    private suspend fun found(query: String): List<ResolvedItem> {
        val items = suggestFood(query).filter { it.candidate != null && it.grams > 0 }
        lock.withLock {
            shown.remove(query)
            shown[query] = items
            while (shown.size > REMEMBERED) shown.remove(shown.keys.first())
        }
        return items
    }

    private companion object {
        const val REMEMBERED = 16
        const val MINUTES_PER_HOUR = 60
        const val DAY_MINUTES = 24 * MINUTES_PER_HOUR
    }
}

private fun DiaryEntryEntity.toWeb(zone: TimeZone): WebEntry {
    val totals = totals()
    val fiber = fiber()
    return WebEntry(
        id = id,
        name = displayName,
        grams = grams,
        meal = meal,
        kcal = totals.kcal,
        protCg = totals.protCg,
        fatCg = totals.fatCg,
        carbCg = totals.carbCg,
        fiberCg = fiber.cg.takeIf { fiber.complete },
        time = formatTime(createdAt, zone),
    )
}
