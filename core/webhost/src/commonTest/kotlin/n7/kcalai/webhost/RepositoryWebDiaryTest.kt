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
import n7.kcalai.model.StepsSettings
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.DEFAULT_STEPS_GOAL
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import n7.kcalai.model.TreadmillSample
import n7.kcalai.database.WalkDao
import n7.kcalai.database.WalkEntity
import n7.kcalai.repositories.NoPhoneSteps
import n7.kcalai.repositories.StepsRepository
import n7.kcalai.repositories.WalkRepository
import n7.kcalai.webapi.WebSaid
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.webapi.WebMacros
import n7.kcalai.webapi.weekOf
import kotlinx.datetime.toLocalDateTime
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
    private val names = mutableListOf<String>()

    private val buckwheat = FoodCandidate(FoodRef.Generic(1), "Гречка отварная", Nutriments(110, 420, 110, 2_130, 280), servingG = 200)
    private val kernel = FoodCandidate(FoodRef.Generic(2), "Гречка ядрица", Nutriments(313, 1_260, 330, 6_200, 1_000), servingG = 60)

    private val walkDao = FakeWalkDao()
    private val walks = WalkRepository(walkDao, TimeZone.UTC)
    private val said = MutableStateFlow<List<WebSaid>>(emptyList())

    /** Цель шагов и рост из профиля телефона. */
    private val stepsGoal = MutableStateFlow(StepsSettings())
    private val savedSteps = mutableListOf<Pair<Int, Int?>>()

    private val web = RepositoryWebDiary(
        diary = DiaryRepository(diaryDao, goalDao, NoContributions),
        walks = walks,
        steps = StepsRepository(walks, NoPhoneSteps, TimeZone.UTC, clock),
        walkSaid = { said },
        live = MutableStateFlow(null),
        hide = {},
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
        rename = { name -> names += name },
        keepPage = {},
        stepsGoal = stepsGoal,
        saveSteps = { goal, height -> savedSteps += goal to height },
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

        web.add(query = "гречка", index = 1, grams = 80, day = today, meal = null)

        val entry = diaryDao.entries.value.single()
        assertEquals("Гречка ядрица", entry.displayName)
        assertEquals(80, entry.grams)
        assertEquals(313, entry.kcal100, "снимок КБЖУ, а не ссылка")
        assertEquals(today, entry.dateEpochDay)
        assertEquals(MealType.BREAKFAST, entry.meal, "в девять утра — завтрак, как и с телефона")
    }

    @Test
    fun `подсказки, которой не было, не записывают`() = runTest {
        web.add(query = "гречка", index = 2, grams = 80, day = today, meal = null)
        web.add(query = "гречка", index = 7, grams = 80, day = today, meal = null)

        assertTrue(diaryDao.entries.value.isEmpty())
    }

    /** Страница листает дни — и прошлые записи правит так же, как сегодняшние. */
    @Test
    fun `прошлый день правится и удаляется`() = runTest {
        val yesterday = diaryDao.insert(entry(day = today - 1))

        web.setGrams(yesterday, 150)
        web.delete(yesterday)

        assertEquals(listOf(yesterday to 150), regrammed)
        assertTrue(diaryDao.entries.value.isEmpty())
    }

    /**
     * Дописанное в прошлый день — в выбранный приём и в его обычный час, как в приложении:
     * ужин, дописанный утром, не должен стать ужином в девять утра.
     */
    @Test
    fun `запись в прошлый день — в выбранный приём и его час`() = runTest {
        web.suggest("гречка")

        web.add(query = "гречка", index = 0, grams = 200, day = today - 2, meal = MealType.DINNER)

        val entry = diaryDao.entries.value.single()
        assertEquals(today - 2, entry.dateEpochDay)
        assertEquals(MealType.DINNER, entry.meal)
        assertEquals(19, Instant.fromEpochMilliseconds(entry.createdAt).toLocalDateTime(TimeZone.UTC).hour)
    }

    @Test
    fun `приём прошлого дня не выбран — первый незаписанный`() = runTest {
        diaryDao.insert(entry(day = today - 1).copy(meal = MealType.BREAKFAST))
        web.suggest("гречка")

        web.add(query = "гречка", index = 0, grams = 200, day = today - 1, meal = null)

        assertEquals(MealType.LUNCH, diaryDao.entries.value.last().meal)
    }

    /** Цель — из граммов, калории складываются сами; действует с сегодня, прошлое не переписывает. */
    @Test
    fun `цель со страницы — с сегодня и с калориями из граммов`() = runTest {
        web.setGoal(WebMacros(prot = 120, fat = 70, carb = 230))

        val goal = goalDao.goalFor(today)!!
        assertEquals(today, goal.fromDateEpochDay)
        assertEquals(120 * 4 + 70 * 9 + 230 * 4, goal.kcal)
        assertEquals(230, goal.carb)
    }

    @Test
    fun `имя со страницы — тому, кто его хранит`() = runTest {
        web.setName("Женя")

        assertEquals(listOf("Женя"), names)
    }

    /** Неделя — с понедельника по воскресенье, с целью каждого дня. */
    @Test
    fun `неделя дня — семь дней с понедельника`() = runTest {
        diaryDao.insert(entry(day = today, grams = 200))

        val week = web.week(today).first()

        assertEquals(7, week.size)
        assertEquals(weekOf(today).toList(), week.map { it.date })
        assertEquals(220, week.single { it.date == today }.kcal)
    }

    /** Прогулки — со временем по часам телефона, шаги дня — их сумма, реплики о них — в ленту дня. */
    @Test
    fun `пакеты дорожки — прогулки дня, шаги и итог Зёрнышка`() = runTest {
        walks.record(TreadmillSample(speedKmh = 4.0, distanceM = 2_140, kcal = 90, elapsedS = 1_920), clock.now().toEpochMilliseconds())
        said.value = listOf(WebSaid(1, Mascot.GRAIN, "Прогулка в копилке", minute = 9 * 60 + 5))

        val shown = web.day(today).first()

        val walk = shown.walks.single()
        assertEquals(8 * 60 + 28, walk.startMinute, "началась 32 минуты назад, в 08:28")
        assertEquals(9 * 60, walk.endMinute)
        assertEquals(2_140, walk.distanceM)
        assertEquals(walk.steps, shown.steps)
        assertEquals(DEFAULT_STEPS_GOAL, shown.stepsGoal)
        assertEquals("Прогулка в копилке", shown.said.single().text)
        assertEquals(walk.steps, web.week(today).first().single { it.date == today }.steps)
    }

    /** Цель шагов на странице — та, что задана на телефоне, и меняется вместе с ней. */
    @Test
    fun `цель шагов и рост — из профиля телефона`() = runTest {
        stepsGoal.value = StepsSettings(goal = 10_000, heightCm = 182)

        val shown = web.day(today).first()
        assertEquals(10_000, shown.stepsGoal)
        assertEquals(182, shown.heightCm)
    }

    @Test
    fun `цель шагов со страницы — в профиль телефона`() = runTest {
        web.setSteps(goal = 12_000, heightCm = null)

        assertEquals(listOf<Pair<Int, Int?>>(12_000 to null), savedSteps)
    }

    @Test
    fun `сегодняшний день — записи с числами на их вес и цель`() = runTest {
        diaryDao.insert(entry(day = today, grams = 200))
        diaryDao.insert(entry(day = today - 1))
        goalDao.upsert(DailyGoalEntity(fromDateEpochDay = today - 3, kcal = 2_100, prot = 100, fat = 70, carb = 250))

        val shown = web.day(today).first()

        assertEquals(2_100, shown.goalKcal)
        assertEquals(WebMacros(prot = 100, fat = 70, carb = 250), shown.goalMacros)
        assertEquals(29, shown.fiberGoal, "своей цели клетчатки нет — по калориям: 14 г на 1000 ккал")
        val only = shown.entries.single()
        assertEquals(220, only.kcal)
        assertEquals(840, only.protCg)
        assertEquals(MealType.LUNCH, only.meal)
        assertEquals(null, only.fiberCg, "клетчатка не указана — неизвестна, а не ноль")
        assertEquals("00:00", only.time)
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

private class FakeWalkDao : WalkDao {
    private val rows = MutableStateFlow<List<WalkEntity>>(emptyList())

    override suspend fun upsert(walk: WalkEntity) = rows.update { list -> (list.filter { it.startedAt != walk.startedAt } + walk).sortedBy { it.startedAt } }

    override suspend fun last(): WalkEntity? = rows.value.lastOrNull()

    override fun observeLast(): Flow<WalkEntity?> = rows.map { it.lastOrNull() }

    override fun observeDays(from: Long, to: Long): Flow<List<WalkEntity>> = rows.map { list -> list.filter { it.dateEpochDay in from..to } }

    override suspend fun day(dateEpochDay: Long): List<WalkEntity> = rows.value.filter { it.dateEpochDay == dateEpochDay }

    override suspend fun markTold(startedAt: Long, at: Long) = rows.update { list -> list.map { if (it.startedAt == startedAt) it.copy(toldAt = at) else it } }
}
