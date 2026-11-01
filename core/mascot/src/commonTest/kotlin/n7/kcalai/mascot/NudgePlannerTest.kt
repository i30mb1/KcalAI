package n7.kcalai.mascot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments

/**
 * Когда персонажи пишут первыми — и, главное, когда молчат.
 *
 * Навязчивый персонаж хуже молчаливого: одно лишнее уведомление — и человек
 * выключает их все. Поэтому почти каждый тест здесь про то, когда НЕ писать.
 */
class NudgePlannerTest {

    private val zone = TimeZone.UTC

    /** Пятница, 6 ноября; сутки по UTC. */
    private fun time(hour: Int, minute: Int = 0) = LocalDateTime(2026, 11, 6, hour, minute)

    private fun snapshot(
        hour: Int,
        rhythm: Rhythm = Rhythm.DEFAULT,
        suggestions: Map<Topic, Suggestion> = emptyMap(),
        facts: Facts.() -> Facts = { this },
    ) = Snapshot(Facts(now = time(hour)).facts(), rhythm, suggestions)

    /** Сегодня ели, но давно: повод для «долго без еды». */
    private val hungry: Facts.() -> Facts = { copy(todayEntries = 2, minutesSinceLastEntry = 200) }

    private fun plan(snapshot: Snapshot, sent: List<Sent> = emptyList(), lastOpenAt: Instant? = null): Nudge? =
        NudgePlanner.plan(snapshot, sent, lastOpenAt, snapshot.facts.now.toInstant(zone), zone)

    private fun Snapshot.ago(duration: Duration): Instant = facts.now.toInstant(zone) - duration

    private fun sent(at: Instant, topic: Topic = Topic.LONG_GAP, mascot: Mascot = Mascot.GRAIN, opened: Boolean = true) =
        Sent(topic, mascot, at, opened)

    private fun food(prot: Int, fat: Int, carb: Int) = FoodCandidate(
        ref = FoodRef.Generic(1),
        displayName = "Еда",
        nutriments = Nutriments(kcal100 = 120, prot100 = prot, fat100 = fat, carb100 = carb),
        servingG = 200,
    )

    // --- Когда молчат ---------------------------------------------------------------------

    @Test
    fun `во сне не пишут`() {
        assertNull(plan(snapshot(23, facts = hungry)))
        assertNull(plan(snapshot(7, facts = hungry)))
        assertEquals(Topic.LONG_GAP, plan(snapshot(16, facts = hungry))?.topic)
    }

    @Test
    fun `сон — по привычкам человека`() {
        val owl = Rhythm(wakeHour = 10, sleepHour = 23)

        assertNull(plan(snapshot(9, owl, facts = hungry)))
        assertEquals(Topic.LONG_GAP, plan(snapshot(16, owl, facts = hungry))?.topic)
    }

    @Test
    fun `только что открывал приложение — не мешают`() {
        val day = snapshot(16, facts = hungry)

        assertNull(plan(day, lastOpenAt = day.ago(30.minutes)))
        assertEquals(Topic.LONG_GAP, plan(day, lastOpenAt = day.ago(2.hours))?.topic)
    }

    @Test
    fun `не чаще раза в три часа`() {
        val day = snapshot(16, facts = hungry)

        assertNull(plan(day, listOf(sent(day.ago(2.hours), Topic.EMPTY_DAY))))
        assertEquals(Topic.LONG_GAP, plan(day, listOf(sent(day.ago(4.hours), Topic.EMPTY_DAY)))?.topic)
    }

    @Test
    fun `не больше двух в день`() {
        val day = snapshot(16, facts = hungry)
        val two = listOf(sent(day.ago(4.hours), Topic.EMPTY_DAY), sent(day.ago(7.hours), Topic.MISSED_MEAL))

        assertNull(plan(day, two))
    }

    @Test
    fun `тема — раз в день`() {
        val day = snapshot(16, facts = hungry)

        assertNull(plan(day, listOf(sent(day.ago(5.hours), Topic.LONG_GAP))))
    }

    @Test
    fun `три проигнорированных подряд — не больше одного в день`() {
        val day = snapshot(16, facts = hungry)
        val ignored = listOf(
            sent(day.ago(5.hours), Topic.EMPTY_DAY, opened = false),
            sent(day.ago(1.days), opened = false),
            sent(day.ago(2.days), opened = false),
        )

        assertNull(plan(day, ignored))
        assertEquals(Topic.LONG_GAP, plan(day, ignored.drop(1) + sent(day.ago(3.days), opened = false))?.topic)
    }

    @Test
    fun `шесть проигнорированных — только «пропал»`() {
        val ignored = (1..6).map { sent(snapshot(16).ago(it.days + 1.hours), opened = false) }

        assertNull(plan(snapshot(16, facts = hungry), ignored))
        assertEquals(Topic.COMEBACK, plan(snapshot(16) { copy(daysAway = 4) }, ignored)?.topic)
    }

    @Test
    fun `открытое приложение прерывает серию игнора`() {
        val day = snapshot(16, facts = hungry)
        val history = listOf(
            sent(day.ago(1.days), opened = true),
            sent(day.ago(2.days), opened = false),
            sent(day.ago(3.days), opened = false),
            sent(day.ago(4.days), opened = false),
        )

        assertEquals(Topic.LONG_GAP, plan(day, history)?.topic)
    }

    // --- Пропал ---------------------------------------------------------------------------

    @Test
    fun `«пропал» — на второй, четвёртый, седьмой и четырнадцатый день, и пишут по очереди`() {
        fun away(days: Int) = plan(snapshot(15) { copy(daysAway = days) })

        assertEquals(Nudge(Topic.COMEBACK, Mascot.GRAIN), away(2))
        assertNull(away(3))
        assertEquals(Nudge(Topic.COMEBACK, Mascot.BUTTER), away(4))
        assertEquals(Nudge(Topic.COMEBACK, Mascot.EGG), away(7))
        assertEquals(Nudge(Topic.COMEBACK, Mascot.BUTTER), away(14))
        assertNull(away(15))
        assertNull(away(40))
    }

    @Test
    fun `«пропал» — не с утра`() {
        assertNull(plan(snapshot(10) { copy(daysAway = 2) }))
    }

    @Test
    fun `пропавшему не пишут про обед`() {
        val missed = mapOf(Topic.MISSED_MEAL to Suggestion(food(prot = 400, fat = 100, carb = 2_000), MealType.LUNCH))
        val away = snapshot(15, suggestions = missed) {
            copy(daysAway = 3, missed = MissedMeal(MealType.LUNCH, 13, "Суп", 300))
        }

        assertNull(plan(away))
    }

    // --- Поводы ---------------------------------------------------------------------------

    @Test
    fun `пропущенный приём — пишет хозяин обычной еды`() {
        fun missed(food: FoodCandidate) = plan(
            snapshot(16, suggestions = mapOf(Topic.MISSED_MEAL to Suggestion(food, MealType.LUNCH))) {
                copy(todayEntries = 1, missed = MissedMeal(MealType.LUNCH, 13, food.displayName, 200))
            }
        )

        assertEquals(Nudge(Topic.MISSED_MEAL, Mascot.EGG), missed(food(prot = 1_700, fat = 500, carb = 300)))
        assertEquals(Nudge(Topic.MISSED_MEAL, Mascot.GRAIN), missed(food(prot = 400, fat = 100, carb = 2_000)))
    }

    @Test
    fun `долго без еды — не ближе двух часов ко сну`() {
        assertEquals(Topic.LONG_GAP, plan(snapshot(19, facts = hungry))?.topic)
        assertNull(plan(snapshot(20, facts = hungry)))
    }

    @Test
    fun `долго без еды — меньше трёх часов не считается`() {
        assertNull(plan(snapshot(16) { copy(todayEntries = 2, minutesSinceLastEntry = 170) }))
    }

    @Test
    fun `долго без еды — Масло и Зёрнышко по очереди`() {
        val day = snapshot(16, facts = hungry)

        assertEquals(Mascot.GRAIN, plan(day)?.mascot)
        assertEquals(Mascot.BUTTER, plan(day, listOf(sent(day.ago(1.days), Topic.LONG_GAP, Mascot.GRAIN)))?.mascot)
        assertEquals(Mascot.GRAIN, plan(day, listOf(sent(day.ago(1.days), Topic.LONG_GAP, Mascot.BUTTER)))?.mascot)
    }

    @Test
    fun `пустой день — после часа дня`() {
        val empty: Facts.() -> Facts = { copy(daysAway = 1, streakDays = 1) }

        assertNull(plan(snapshot(12, facts = empty)))
        assertEquals(Topic.EMPTY_DAY, plan(snapshot(13, facts = empty))?.topic)
    }

    @Test
    fun `серия под угрозой — ближе к вечеру, пишет Яйцо`() {
        val streak: Facts.() -> Facts = { copy(daysAway = 1, streakDays = 5) }

        assertEquals(Topic.EMPTY_DAY, plan(snapshot(15, facts = streak))?.topic)
        assertEquals(Nudge(Topic.STREAK_KEEP, Mascot.EGG), plan(snapshot(18, facts = streak)))
    }

    @Test
    fun `белок — вечером и от двадцати пяти граммов`() {
        val protein: Facts.() -> Facts = { copy(todayEntries = 2, minutesSinceLastEntry = 60, protLeftG = 40) }

        assertEquals(Nudge(Topic.PROTEIN_LEFT, Mascot.EGG), plan(snapshot(18, facts = protein)))
        assertNull(plan(snapshot(14, facts = protein)))
        assertNull(plan(snapshot(18) { copy(todayEntries = 2, minutesSinceLastEntry = 60, protLeftG = 20) }))
    }

    @Test
    fun `«вчера в цель» — утром, пока сегодня пусто, и не чаще раза в три дня`() {
        val win = snapshot(10) { copy(yesterdayOnGoal = true) }

        assertEquals(Nudge(Topic.YESTERDAY_WIN, Mascot.BUTTER), plan(win))
        assertNull(plan(win, listOf(sent(win.ago(2.days), Topic.YESTERDAY_WIN, Mascot.BUTTER))))
        assertEquals(Topic.YESTERDAY_WIN, plan(win, listOf(sent(win.ago(4.days), Topic.YESTERDAY_WIN, Mascot.BUTTER)))?.topic)
        assertNull(plan(snapshot(14) { copy(yesterdayOnGoal = true) }))
    }

    @Test
    fun `свежая установка без записей — молчат`() {
        assertNull(plan(snapshot(16)))
    }
}
