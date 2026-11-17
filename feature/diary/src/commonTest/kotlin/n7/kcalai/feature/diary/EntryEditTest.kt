package n7.kcalai.feature.diary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import n7.kcalai.model.FiberTotal
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Правка записи: порция и продукт — отдельно, а клетчатку в шапке объясняет Зёрнышко.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = DiaryHarness()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.screen(): DiaryViewModel =
        harness.viewModel(dispatcher).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

    /** Шит остаётся открытым и показывает уже поправленный продукт: вторая правка сравнивается с ним. */
    @Test
    fun `правка продукта меняет запись и открытый шит а вес остаётся`() = runTest(dispatcher) {
        val entry = harness.addEntry("Псиллиум", grams = 100, meal = MealType.LUNCH, kcal100 = 42)
        val vm = screen()
        vm.onEditEntry(entry)

        vm.onCorrectProduct(entry, Nutriments(kcal100 = 35, prot100 = 290, fat100 = 10, carb100 = 730, fiber100 = 8000))
        advanceUntilIdle()

        val saved = harness.diaryDao.day(harness.today).single()
        assertEquals(35, saved.kcal100)
        assertEquals(8000, saved.fiber100)
        assertEquals(100, saved.grams)
        val open = assertIs<Overlay.EditEntry>(vm.state.value.overlay).entry
        assertEquals(35, open.kcal100)
        assertEquals(8000, open.fiber100)
    }

    @Test
    fun `сохранение порции не трогает цифры продукта`() = runTest(dispatcher) {
        val entry = harness.addEntry("Псиллиум", grams = 100, meal = MealType.LUNCH, kcal100 = 42)
        val vm = screen()

        vm.onSaveEntry(entry, grams = 150, meal = MealType.DINNER)
        advanceUntilIdle()

        val saved = harness.diaryDao.day(harness.today).single()
        assertEquals(42, saved.kcal100)
        assertNull(saved.fiber100)
        assertEquals(MealType.DINNER, saved.meal)
    }

    @Test
    fun `тап по клетчатке в шапке — Зёрнышко говорит что это клетчатка`() = runTest(dispatcher) {
        val vm = screen()

        vm.onFiberTapped()
        runCurrent()

        val bubble = vm.state.value.speech.single()
        assertEquals(Mascot.GRAIN, bubble.mascot)
        assertTrue("клетчатка" in bubble.text.lowercase())
    }

    @Test
    fun `Зёрнышко называет клетчатку дня и цель`() {
        assertTrue("12 из 25 г" in fiberSpeech(FiberTotal(cg = 1_200, unknown = 0), goalGrams = 25))
    }

    /** Неизвестная клетчатка — не ноль: Зёрнышко не выдумывает «0 из 25». */
    @Test
    fun `неизвестную клетчатку Зёрнышко нулём не называет`() {
        val text = fiberSpeech(FiberTotal(cg = 0, unknown = 1), goalGrams = 25)

        assertFalse("0 из" in text)
        assertTrue("клетчатка" in text.lowercase())
    }
}
