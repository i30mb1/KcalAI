package n7.kcalai.feature.diary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import n7.kcalai.database.MascotProfileEntity
import n7.kcalai.model.DEFAULT_STEPS_GOAL
import n7.kcalai.model.MealType
import n7.kcalai.repositories.DaySteps
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Шаги дня — тихой плашкой в ленте, после записей; тап по ней открывает цель шагов и рост.
 * Нет шагов (телефон не отдаёт, по дорожке не ходили) — нет и плашки.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StepsTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = DiaryHarness()
    private val counted = MutableStateFlow(DaySteps(phone = 2_010, treadmill = 3_232))
    private val asked = mutableListOf<Long>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun daySteps(day: Long): Flow<DaySteps> {
        asked += day
        return counted
    }

    private fun TestScope.screen(): DiaryViewModel =
        harness.viewModel(dispatcher, daySteps = ::daySteps).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

    private fun DiaryViewModel.steps(): FeedItem.Steps? = state.value.feed.filterIsInstance<FeedItem.Steps>().singleOrNull()

    @Test
    fun `шаги дня — плашкой после записей, цель по умолчанию`() = runTest(dispatcher) {
        harness.addEntry("Овсянка", grams = 60, meal = MealType.BREAKFAST)
        val vm = screen()

        val feed = vm.state.value.feed
        val shown = vm.steps()
        assertEquals(FeedItem.Steps(steps = 5_242, goal = DEFAULT_STEPS_GOAL), shown)
        assertTrue(feed.indexOf(shown as FeedItem) > feed.indexOfLast { it is FeedItem.Meal }, "плашка — после записей")
        assertEquals(listOf(harness.today), asked.distinct())
    }

    @Test
    fun `цель — из профиля`() = runTest(dispatcher) {
        harness.mascotDao.upsertProfile(MascotProfileEntity(stepsGoal = 10_000))
        val vm = screen()

        assertEquals(10_000, vm.steps()?.goal)
    }

    @Test
    fun `шагов нет — плашки нет`() = runTest(dispatcher) {
        counted.value = DaySteps(phone = null, treadmill = 0)
        val vm = screen()

        assertNull(vm.steps())
    }

    /** Утро: доступ к шагомеру есть, а шагов ещё ноль — плашка стоит, ей есть куда расти. */
    @Test
    fun `сегодня с шагомером — плашка и при нуле`() = runTest(dispatcher) {
        counted.value = DaySteps(phone = 0, treadmill = 0)
        val vm = screen()

        assertEquals(0, vm.steps()?.steps)
    }

    @Test
    fun `шаги считаются заново — плашка обновляется на месте`() = runTest(dispatcher) {
        val vm = screen()

        counted.value = DaySteps(phone = 4_000, treadmill = 3_232)
        advanceUntilIdle()

        assertEquals(7_232, vm.steps()?.steps)
        assertEquals(1, vm.state.value.feed.count { it is FeedItem.Steps })
    }

    @Test
    fun `тап по плашке открывает цель шагов и рост, сохранение — в профиль`() = runTest(dispatcher) {
        harness.mascotDao.upsertProfile(MascotProfileEntity(heightCm = 170))
        val vm = screen()

        vm.onOpenSteps()
        advanceUntilIdle()
        assertEquals(Overlay.Steps(goal = DEFAULT_STEPS_GOAL, heightCm = 170), vm.state.value.overlay)

        vm.onSetSteps(goal = 10_000, heightCm = 182)
        advanceUntilIdle()

        assertEquals(Overlay.None, vm.state.value.overlay)
        assertEquals(10_000, harness.mascotDao.profile()?.stepsGoal)
        assertEquals(182, harness.mascotDao.profile()?.heightCm)
        assertEquals(10_000, vm.steps()?.goal)
    }

    @Test
    fun `рост из помощника цели — в профиль`() = runTest(dispatcher) {
        val vm = screen()

        vm.onHeight(176)
        advanceUntilIdle()

        assertEquals(176, harness.mascotDao.profile()?.heightCm)
    }

    @Test
    fun `цель шагов — от тысячи до пятидесяти тысяч`() {
        assertEquals(10_000, stepsGoalInput("10000"))
        assertEquals(1_000, stepsGoalInput("1000"))
        assertNull(stepsGoalInput("999"))
        assertNull(stepsGoalInput("50001"))
        assertNull(stepsGoalInput(""))
    }

    @Test
    fun `рост — от ста двадцати до двухсот тридцати`() {
        assertEquals(182, heightInput("182"))
        assertNull(heightInput("119"))
        assertNull(heightInput("231"))
        assertNull(heightInput(""))
    }
}
