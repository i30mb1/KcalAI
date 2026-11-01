package n7.kcalai.mascot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.MascotProfileEntity
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType
import n7.kcalai.model.Sex
import n7.kcalai.personal.PersonalRepository

/**
 * Что персонажи знают о дне человека.
 *
 * Каждое число здесь потом окажется в реплике, поэтому ошибка в факте — это
 * персонаж, который врёт: «серия шесть дней», когда вчера было пусто.
 */
class FactsBuilderTest {

    private val zone = TimeZone.UTC
    private val diary = FakeDiaryDao()
    private val goals = FakeGoalDao()
    private val builder = FactsBuilder(
        diaryDao = diary,
        goalDao = goals,
        personal = PersonalRepository(diary, FakePersonalDao(), FakeBodyMetricDao(), goals, zone),
        zone = zone,
    )

    /** Пятница, 16:10. */
    private val now = Instant.parse("2026-11-06T16:10:00Z")
    private val today = LocalDate(2026, 11, 6).toEpochDays()

    private suspend fun eat(
        name: String,
        daysAgo: Int,
        hour: Int,
        minute: Int = 0,
        meal: MealType = MealType.LUNCH,
        grams: Int = 100,
        kcal100: Int = 100,
        prot100: Int = 1_000,
    ) {
        val day = today - daysAgo
        diary.insert(
            DiaryEntryEntity(
                dateEpochDay = day,
                meal = meal,
                displayName = name,
                grams = grams,
                kcal100 = kcal100,
                prot100 = prot100,
                fat100 = 300,
                carb100 = 1_000,
                source = EntrySource.TEXT,
                foodRef = "generic:${name.length * 31 + name.first().code}",
                createdAt = (day * DAY_MINUTES + hour * 60 + minute) * 60_000L,
            )
        )
    }

    private suspend fun build(profile: MascotProfileEntity? = null) = builder.build(now, profile)

    @Test
    fun `серия — подряд дни до вчера`() = runTest {
        listOf(1, 2, 3, 5).forEach { eat("Каша", daysAgo = it, hour = 9) }

        assertEquals(3, build().facts.streakDays)
    }

    @Test
    fun `сегодняшнее в серию не идёт, но и не рвёт её`() = runTest {
        listOf(0, 1, 2).forEach { eat("Каша", daysAgo = it, hour = 9) }

        assertEquals(2, build().facts.streakDays)
    }

    @Test
    fun `сколько дней не было`() = runTest {
        eat("Каша", daysAgo = 4, hour = 9)

        assertEquals(4, build().facts.daysAway)
    }

    @Test
    fun `минуты с последней записи`() = runTest {
        eat("Гречка отварная", daysAgo = 0, hour = 12, minute = 55)

        val facts = build().facts

        assertEquals(195, facts.minutesSinceLastEntry)
        assertEquals("Гречка отварная", facts.lastFood)
        assertEquals(LocalTime(12, 55), facts.lastEntryAt)
        assertEquals(1, facts.todayEntries)
    }

    @Test
    fun `вчера в цель — в пределах десяти процентов`() = runTest {
        goals.upsert(DailyGoalEntity(today - 10, kcal = 2_000, prot = 120, fat = 70, carb = 220))
        eat("Обед", daysAgo = 1, hour = 13, grams = 1_900)

        assertTrue(build().facts.yesterdayOnGoal)
    }

    @Test
    fun `перебор вчера — не в цель`() = runTest {
        goals.upsert(DailyGoalEntity(today - 10, kcal = 2_000, prot = 120, fat = 70, carb = 220))
        eat("Обед", daysAgo = 1, hour = 13, grams = 2_300)

        assertFalse(build().facts.yesterdayOnGoal)
    }

    @Test
    fun `любимое — то, что в истории хотя бы трижды`() = runTest {
        listOf(1, 2, 3).forEach { eat("Овсянка на молоке", daysAgo = it, hour = 8, meal = MealType.BREAKFAST) }
        listOf(1, 2).forEach { eat("Суп", daysAgo = it, hour = 13) }

        assertEquals("Овсянка на молоке", build().facts.favorite)
    }

    @Test
    fun `дважды — ещё не любимое`() = runTest {
        listOf(1, 2).forEach { eat("Суп", daysAgo = it, hour = 13) }

        assertNull(build().facts.favorite)
    }

    @Test
    fun `остаток белка только при цели белка`() = runTest {
        goals.upsert(DailyGoalEntity(today, kcal = 2_000, prot = 120, fat = 70, carb = 220))
        eat("Курица", daysAgo = 0, hour = 13, grams = 365, prot100 = 2_000)

        assertEquals(47, build().facts.protLeftG)
    }

    @Test
    fun `без цели белка — без остатка`() = runTest {
        goals.upsert(DailyGoalEntity(today, kcal = 2_000, prot = 0, fat = 0, carb = 0))
        eat("Курица", daysAgo = 0, hour = 13)

        assertNull(build().facts.protLeftG)
    }

    @Test
    fun `пропущенный обед с обычной едой и подсказкой`() = runTest {
        (1..6).forEach { eat("Суп куриный", daysAgo = it, hour = 13, grams = 300) }
        eat("Каша", daysAgo = 0, hour = 8, meal = MealType.BREAKFAST)

        val snapshot = build()

        val missed = snapshot.facts.missed
        assertEquals(MealType.LUNCH, missed?.meal)
        assertEquals(13, missed?.usualHour)
        assertEquals("Суп куриный", missed?.usualFood)
        assertEquals(300, missed?.usualGrams)
        val suggestion = snapshot.suggestions[Topic.MISSED_MEAL]
        assertEquals("Суп куриный", suggestion?.candidate?.displayName)
        assertEquals(MealType.LUNCH, suggestion?.meal)
    }

    @Test
    fun `ритм по привычкам`() = runTest {
        (1..6).forEach {
            eat("Каша", daysAgo = it, hour = 7, meal = MealType.BREAKFAST)
            eat("Рыба", daysAgo = it, hour = 21, meal = MealType.DINNER)
        }

        val snapshot = build()

        assertEquals(Rhythm(wakeHour = 6, sleepHour = 23), snapshot.rhythm)
        assertEquals(Chronotype.LARK, snapshot.facts.chronotype)
    }

    @Test
    fun `пустая история — факты пустые, без падений`() = runTest {
        val snapshot = build()

        with(snapshot.facts) {
            assertNull(minutesSinceLastEntry)
            assertNull(lastFood)
            assertEquals(0, daysAway)
            assertEquals(0, streakDays)
            assertNull(favorite)
            assertNull(missed)
            assertNull(protLeftG)
            assertNull(chronotype)
        }
        assertEquals(Rhythm(wakeHour = 8, sleepHour = 22), snapshot.rhythm)
        assertTrue(snapshot.suggestions.isEmpty())
    }

    @Test
    fun `имя, пол и отсутствие — из профиля`() = runTest {
        val profile = MascotProfileEntity(name = "Женя", sex = Sex.FEMALE, lastOpenAt = now.toEpochMilliseconds() - 300 * 60_000L)

        val facts = build(profile).facts

        assertEquals("Женя", facts.name)
        assertEquals(Sex.FEMALE, facts.sex)
        assertEquals(300, facts.absentMinutes)
    }

    private companion object {
        const val DAY_MINUTES = 24 * 60L
    }
}
