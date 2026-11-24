package n7.kcalai.webhost

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.totals
import n7.kcalai.model.EntrySource
import n7.kcalai.model.forGrams
import n7.kcalai.model.owner
import n7.kcalai.personal.mealForHour
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.webapi.WebEntry
import n7.kcalai.webapi.WebMacros
import n7.kcalai.webapi.WebSuggestion
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
 */
class RepositoryWebDiary(
    private val diary: DiaryRepository,
    private val suggestFood: suspend (String) -> List<ResolvedItem>,
    private val regram: suspend (entryId: Long, grams: Int, now: Long) -> Unit,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : WebDiary {

    private val lock = Mutex()

    /** Последние выдачи по запросам, старые первыми. Браузеров бывает несколько, и запросы у них свои. */
    private val shown = LinkedHashMap<String, List<ResolvedItem>>()

    override fun today(): Flow<WebToday> = flow {
        val day = todayEpochDay()
        emitAll(
            combine(diary.observeDay(day), diary.observeGoal(day)) { totals, goal ->
                WebToday(
                    entries = totals.entries.map { it.toWeb() },
                    goalKcal = goal?.kcal?.takeIf { it > 0 },
                    goalMacros = goal?.takeIf { it.prot > 0 && it.fat > 0 && it.carb > 0 }?.let { WebMacros(it.prot, it.fat, it.carb) },
                )
            }
        )
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

    override suspend fun add(query: String, index: Int, grams: Int) {
        val candidate = (lock.withLock { shown[query] } ?: found(query)).getOrNull(index)?.candidate ?: return
        val day = todayEpochDay()
        val now = clock.now()
        val meal = mealForHour(
            hour = now.toLocalDateTime(zone).hour,
            lastEntry = diary.observeDay(day).first().entries.maxByOrNull { it.createdAt },
            nowMillis = now.toEpochMilliseconds(),
        )
        diary.add(candidate, grams, meal, day, EntrySource.TEXT, now.toEpochMilliseconds())
    }

    override suspend fun setGrams(entryId: Long, grams: Int) {
        if (todays(entryId)) regram(entryId, grams, clock.now().toEpochMilliseconds())
    }

    override suspend fun delete(entryId: Long) {
        if (todays(entryId)) diary.delete(entryId)
    }

    /** Страница показывает только сегодня — и трогать может только сегодняшнее. */
    private suspend fun todays(entryId: Long): Boolean = diary.entry(entryId)?.dateEpochDay == todayEpochDay()

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

    private fun todayEpochDay(): Long = clock.todayIn(zone).toEpochDays()

    private companion object {
        const val REMEMBERED = 16
    }
}

private fun DiaryEntryEntity.toWeb(): WebEntry {
    val totals = totals()
    return WebEntry(
        id = id,
        name = displayName,
        grams = grams,
        meal = meal,
        kcal = totals.kcal,
        protCg = totals.protCg,
        fatCg = totals.fatCg,
        carbCg = totals.carbCg,
    )
}
