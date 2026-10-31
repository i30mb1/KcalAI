package n7.kcalai.feature.diary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.model.MealType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Один день — одна переписка.
 *
 * Тап по дню недели открывает его ленту целиком: что было записано, и в неё же
 * можно дописать забытое. Вопросы «про сейчас» — пропущенный обед, остаток,
 * утренняя сводка — живут только в сегодняшней переписке: про вчера их задавать
 * поздно.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayNavigationTest {

    private val dispatcher = StandardTestDispatcher()

    /** Суббота, 31 октября, 09:00 по UTC. */
    private val harness = DiaryHarness()

    private val yesterday: Long get() = harness.today - 1

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.screen(): DiaryViewModel =
        harness.viewModel(dispatcher).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

    private fun DiaryViewModel.shownNames(): List<String> =
        state.value.feed.filterIsInstance<FeedItem.Meal>().flatMap { it.entries }.map { it.displayName }

    @Test
    fun `тап по прошлому дню открывает его переписку — и только её`() = runTest(dispatcher) {
        harness.addEntry("Гречка", grams = 200, meal = MealType.LUNCH, daysAgo = 1)
        harness.addEntry("Омлет", grams = 150, meal = MealType.BREAKFAST)
        val vm = screen()

        vm.onSelectDay(yesterday)
        advanceUntilIdle()

        assertEquals(yesterday, vm.state.value.date.toEpochDays())
        assertEquals("вчера", (vm.state.value.feed.first() as FeedItem.DaySeparator).label)
        assertEquals(listOf("Гречка"), vm.shownNames())
    }

    @Test
    fun `сегодняшняя переписка возвращается тапом по сегодня`() = runTest(dispatcher) {
        harness.addEntry("Омлет", grams = 150, meal = MealType.BREAKFAST)
        val vm = screen()

        vm.onSelectDay(yesterday)
        advanceUntilIdle()
        vm.onSelectDay(harness.today)
        advanceUntilIdle()

        assertEquals(harness.today, vm.state.value.date.toEpochDays())
        assertEquals("сегодня", (vm.state.value.feed.first() as FeedItem.DaySeparator).label)
        assertEquals(listOf("Омлет"), vm.shownNames())
        assertNull(vm.state.value.pastMeal)
    }

    /** Утреннее «Сегодня можно 2000 ккал» над вчерашним днём было бы неправдой. */
    @Test
    fun `в пустом прошлом дне нет утренней сводки — только приглашение дописать`() = runTest(dispatcher) {
        harness.goalDao.upsert(DailyGoalEntity(fromDateEpochDay = harness.today - 10, kcal = 2000, prot = 100, fat = 60, carb = 250))
        val vm = screen()

        vm.onSelectDay(yesterday)
        advanceUntilIdle()

        val feed = vm.state.value.feed
        assertTrue(feed.none { it is FeedItem.Summary })
        assertTrue(feed.any { it is FeedItem.EmptyDay })
    }

    /**
     * Дописанное во вчера ложится во вчера, в выбранный приём и в его время.
     *
     * Не «сейчас»: ужин, дописанный утром, получил бы девять утра, встал бы
     * в ленте не на своё место и научил бы модели привычек, что ужинают в девять.
     */
    @Test
    fun `запись в прошлый день ложится в этот день, в выбранный приём и в его время`() = runTest(dispatcher) {
        val vm = screen()

        vm.onSelectDay(yesterday)
        advanceUntilIdle()
        vm.onPickMeal(MealType.DINNER)
        vm.onPickPrediction(harness.kefir)
        advanceUntilIdle()

        val added = harness.diaryDao.day(yesterday).single()
        assertEquals(MealType.DINNER, added.meal)
        assertEquals(atLocal(yesterday, 19, 0), added.createdAt)
        assertTrue(harness.diaryDao.day(harness.today).isEmpty())
    }

    /** К уже записанному приёму дописанное встаёт следом, а не в его начало. */
    @Test
    fun `дописанное к приёму прошлого дня встаёт следом за его записями`() = runTest(dispatcher) {
        val lunch = harness.addEntry("Гречка", grams = 200, meal = MealType.LUNCH, daysAgo = 1)
        val vm = screen()

        vm.onSelectDay(yesterday)
        advanceUntilIdle()
        vm.onPickMeal(MealType.LUNCH)
        vm.onPickPrediction(harness.kefir)
        advanceUntilIdle()

        val added = harness.diaryDao.day(yesterday).last()
        assertEquals("Кефир 1%", added.displayName)
        assertEquals(lunch.createdAt + 60_000L, added.createdAt)
    }

    /** Возвращаются в прошлый день чаще всего за забытым приёмом — его и предлагаем. */
    @Test
    fun `приём по умолчанию в прошлом дне — первый незаписанный`() = runTest(dispatcher) {
        harness.addEntry("Омлет", grams = 150, meal = MealType.BREAKFAST, daysAgo = 1)
        val vm = screen()

        vm.onSelectDay(yesterday)
        advanceUntilIdle()

        assertEquals(MealType.LUNCH, vm.state.value.pastMeal)
    }

    @Test
    fun `вес, записанный в прошлом дне, — вес того дня, и реплика живёт там же`() = runTest(dispatcher) {
        val vm = screen()

        vm.onSelectDay(yesterday)
        advanceUntilIdle()
        vm.onInputChange("вес 82,4")
        vm.onLogWeight()
        advanceUntilIdle()

        assertEquals(82_400, harness.bodyMetricDao.weights[yesterday])
        assertTrue(vm.state.value.feed.any { it is FeedItem.WeightLogged })

        vm.onSelectDay(harness.today)
        advanceUntilIdle()
        assertTrue(vm.state.value.feed.none { it is FeedItem.WeightLogged })
    }

    @Test
    fun `удалённая в прошлом дне запись возвращается в свой день`() = runTest(dispatcher) {
        val lunch = harness.addEntry("Гречка", grams = 200, meal = MealType.LUNCH, daysAgo = 1)
        val vm = screen()

        vm.onSelectDay(yesterday)
        advanceUntilIdle()
        vm.onDeleteEntry(lunch)
        advanceUntilIdle()
        vm.onUndoDelete(vm.state.value.feed.filterIsInstance<FeedItem.Deleted>().single())
        advanceUntilIdle()

        assertEquals(listOf(lunch), harness.diaryDao.day(yesterday))
    }

    /** Про вчерашний перебор «завтра это учтётся» — уже неправда: завтра наступило. */
    @Test
    fun `перебор в прошлом дне — итог, а не обещание на завтра`() = runTest(dispatcher) {
        harness.goalDao.upsert(DailyGoalEntity(fromDateEpochDay = harness.today - 10, kcal = 1000, prot = 50, fat = 30, carb = 120))
        harness.addEntry("Пицца", grams = 500, meal = MealType.DINNER, daysAgo = 1, kcal100 = 300)
        val vm = screen()

        vm.onSelectDay(yesterday)
        advanceUntilIdle()

        val over = vm.state.value.feed.filterIsInstance<FeedItem.Over>().single()
        assertEquals(500, over.overKcal)
        assertTrue(over.past)
    }

    private fun atLocal(day: Long, hour: Int, minute: Int): Long =
        LocalDateTime(LocalDate.fromEpochDays(day), LocalTime(hour, minute))
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
}
