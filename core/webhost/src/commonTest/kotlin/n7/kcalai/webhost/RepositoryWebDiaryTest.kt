package n7.kcalai.webhost

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import n7.kcalai.database.ContributionDao
import n7.kcalai.database.ContributionEntity
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryDao
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.GoalDao
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.webapi.WebMacros
import n7.kcalai.webapi.WebSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Страница пишет в дневник тем же путём, что строка ввода: тот же поиск, тот же
 * снимок КБЖУ, приём пищи — по часам, как у записи с телефона.
 */
class RepositoryWebDiaryTest {

    private val diaryDao = FakeDiaryDao()
    private val goalDao = FakeGoalDao()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-11-24T09:00:00Z")
    }
    private val today = clock.todayIn(TimeZone.UTC).toEpochDays()
    private val regrammed = mutableListOf<Pair<Long, Int>>()

    private val buckwheat = FoodCandidate(FoodRef.Generic(1), "Гречка отварная", Nutriments(110, 420, 110, 2_130, 280), servingG = 200)
    private val kernel = FoodCandidate(FoodRef.Generic(2), "Гречка ядрица", Nutriments(313, 1_260, 330, 6_200, 1_000), servingG = 60)

    private val web = RepositoryWebDiary(
        diary = DiaryRepository(diaryDao, goalDao, NoContributions),
        suggestFood = { query ->
            if ("греч" in query) {
                listOf(
                    ResolvedItem(query, buckwheat, grams = 200, confidence = 1f, source = EntrySource.TEXT),
                    ResolvedItem(query, kernel, grams = 60, confidence = 1f, source = EntrySource.TEXT),
                    ResolvedItem(query, candidate = null, grams = 0, confidence = 0f, source = EntrySource.TEXT),
                )
            } else {
                emptyList()
            }
        },
        regram = { id, grams, _ -> regrammed += id to grams },
        clock = clock,
        zone = TimeZone.UTC,
    )

    @Test
    fun `подсказки — с калориями на свою порцию, без неопознанного`() = runTest {
        assertEquals(
            listOf(
                WebSuggestion("Гречка отварная", 200, 220, Mascot.GRAIN),
                WebSuggestion("Гречка ядрица", 60, 188, Mascot.GRAIN),
            ),
            web.suggest("гречка"),
        )
    }

    @Test
    fun `запись — та подсказка, что была на странице, с граммами со страницы`() = runTest {
        web.suggest("гречка")

        web.add(query = "гречка", index = 1, grams = 80)

        val entry = diaryDao.entries.value.single()
        assertEquals("Гречка ядрица", entry.displayName)
        assertEquals(80, entry.grams)
        assertEquals(313, entry.kcal100, "снимок КБЖУ, а не ссылка")
        assertEquals(today, entry.dateEpochDay)
        assertEquals(MealType.BREAKFAST, entry.meal, "в девять утра — завтрак, как и с телефона")
    }

    @Test
    fun `подсказки, которой не было, не записывают`() = runTest {
        web.add(query = "гречка", index = 2, grams = 80)
        web.add(query = "гречка", index = 7, grams = 80)

        assertTrue(diaryDao.entries.value.isEmpty())
    }

    /** Страница показывает только сегодня — и править может только сегодня. */
    @Test
    fun `вчерашнее со страницы не правится и не удаляется`() = runTest {
        val yesterday = diaryDao.insert(entry(day = today - 1))
        val now = diaryDao.insert(entry(day = today))

        web.setGrams(yesterday, 150)
        web.delete(yesterday)
        web.setGrams(now, 150)

        assertEquals(listOf(now to 150), regrammed)
        assertEquals(2, diaryDao.entries.value.size)

        web.delete(now)
        assertEquals(listOf(today - 1), diaryDao.entries.value.map { it.dateEpochDay })
    }

    @Test
    fun `сегодняшний день — записи с числами на их вес и цель`() = runTest {
        diaryDao.insert(entry(day = today, grams = 200))
        diaryDao.insert(entry(day = today - 1))
        goalDao.upsert(DailyGoalEntity(fromDateEpochDay = today - 3, kcal = 2_100, prot = 100, fat = 70, carb = 250))

        val shown = web.today().first()

        assertEquals(2_100, shown.goalKcal)
        assertEquals(WebMacros(prot = 100, fat = 70, carb = 250), shown.goalMacros)
        val only = shown.entries.single()
        assertEquals(220, only.kcal)
        assertEquals(840, only.protCg)
        assertEquals(MealType.LUNCH, only.meal)
    }

    private fun entry(day: Long, grams: Int = 200) = DiaryEntryEntity(
        dateEpochDay = day,
        meal = MealType.LUNCH,
        displayName = "Гречка отварная",
        grams = grams,
        kcal100 = 110,
        prot100 = 420,
        fat100 = 110,
        carb100 = 2_130,
        source = EntrySource.TEXT,
        foodRef = null,
        createdAt = 0,
    )
}

private class FakeDiaryDao : DiaryDao {
    val entries = MutableStateFlow<List<DiaryEntryEntity>>(emptyList())

    override suspend fun insert(entry: DiaryEntryEntity): Long {
        val id = if (entry.id != 0L) entry.id else (entries.value.maxOfOrNull { it.id } ?: 0) + 1
        entries.update { it + entry.copy(id = id) }
        return id
    }

    override fun observeDay(dateEpochDay: Long): Flow<List<DiaryEntryEntity>> =
        entries.map { list -> list.filter { it.dateEpochDay == dateEpochDay } }

    override suspend fun delete(id: Long) = entries.update { list -> list.filterNot { it.id == id } }

    override suspend fun since(fromEpochDay: Long) = entries.value.filter { it.dateEpochDay >= fromEpochDay }

    override fun observeSince(fromEpochDay: Long) = entries.map { list -> list.filter { it.dateEpochDay >= fromEpochDay } }

    override suspend fun findById(id: Long) = entries.value.find { it.id == id }

    override suspend fun updateGrams(id: Long, grams: Int) =
        entries.update { list -> list.map { if (it.id == id) it.copy(grams = grams) else it } }

    override suspend fun updateMeal(id: Long, meal: MealType) =
        entries.update { list -> list.map { if (it.id == id) it.copy(meal = meal) else it } }

    override suspend fun updateProduct(id: Long, name: String, kcal: Int, prot: Int, fat: Int, carb: Int, fiber: Int?) = Unit
}

private class FakeGoalDao : GoalDao {
    private val goals = MutableStateFlow<List<DailyGoalEntity>>(emptyList())

    override suspend fun upsert(goal: DailyGoalEntity) =
        goals.update { list -> (list.filterNot { it.fromDateEpochDay == goal.fromDateEpochDay } + goal).sortedBy { it.fromDateEpochDay } }

    override suspend fun goalFor(dateEpochDay: Long) = goals.value.lastOrNull { it.fromDateEpochDay <= dateEpochDay }

    override fun observeGoalFor(dateEpochDay: Long) = goals.map { list -> list.lastOrNull { it.fromDateEpochDay <= dateEpochDay } }

    override fun observeAll() = goals
}

private object NoContributions : ContributionDao {
    override suspend fun insert(contribution: ContributionEntity): Long = 0
    override suspend fun pending(limit: Int): List<ContributionEntity> = emptyList()
    override suspend fun pendingCount(): Int = 0
    override suspend fun markSent(ids: List<Long>, sentAt: Long) = Unit
}
