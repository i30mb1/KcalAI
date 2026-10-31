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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Экранная модель дневника глазами человека: что он набрал, что нажал
 * и что после этого стоит в ленте и в дневнике.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = DiaryHarness()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Модель с подписчиком на состояние: без него `stateIn` не считает ленту. */
    private fun TestScope.screen(): DiaryViewModel =
        harness.viewModel(dispatcher).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

    private fun DiaryViewModel.type(text: String) = onInputChange(text)

    @Test
    fun `вес из строки ввода записывается тапом по чипсу`() = runTest(dispatcher) {
        val vm = screen()

        vm.type("вес 82,4")
        advanceUntilIdle()
        vm.onLogWeight()
        advanceUntilIdle()

        assertEquals(82_400, harness.bodyMetricDao.weights[harness.today])
        assertEquals("", vm.state.value.input)
        assertTrue(vm.state.value.feed.any { it is FeedItem.WeightLogged })
    }

    // --- Холодный старт ------------------------------------------------------------------

    /**
     * Пока база не ответила, дня ещё нет — и рисовать вместо него пустой день
     * нельзя. Раньше на каждом холодном старте полсекунды висело «0 съедено»
     * и «Цель на сегодня не задана», а потом настоящие записи выпрыгивали
     * с анимацией, будто их только что добавили.
     */
    @Test
    fun `пока день не прочитан из базы, лента не выдумывает пустой день`() = runTest(dispatcher) {
        harness.addEntry("Гречка отварная", grams = 200, meal = n7.kcalai.model.MealType.LUNCH)
        harness.diaryDao.holdDay()
        val vm = screen()

        assertFalse(vm.state.value.loaded)
        assertTrue(vm.state.value.feed.isEmpty())

        harness.diaryDao.releaseDay()
        advanceUntilIdle()

        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.feed.any { it is FeedItem.Meal })
        assertTrue(vm.state.value.feed.none { it is FeedItem.Summary })
    }

    // --- Отмена удаления ------------------------------------------------------------------

    /**
     * Удаление идёт без вопроса «вы уверены?», и это правильно — но только пока
     * ошибку можно отменить. Без отмены случайный тап стирает запись насовсем.
     */
    @Test
    fun `удалённую запись возвращает «Вернуть» — та же запись, на том же месте`() = runTest(dispatcher) {
        val entry = harness.addEntry("Гречка отварная", grams = 200, meal = n7.kcalai.model.MealType.LUNCH)
        val vm = screen()

        vm.onDeleteEntry(entry)
        advanceUntilIdle()

        assertTrue(harness.diaryDao.day(harness.today).isEmpty())
        val deleted = vm.state.value.feed.filterIsInstance<FeedItem.Deleted>().single()
        assertEquals(entry, deleted.entry)

        vm.onUndoDelete(deleted)
        advanceUntilIdle()

        assertEquals(listOf(entry), harness.diaryDao.day(harness.today))
        assertTrue(vm.state.value.feed.none { it is FeedItem.Deleted })
    }

    /**
     * Реплика «Удалил…» уходит из ленты после отмены. Номер следующей реплики
     * не вправе совпасть с номером уже стоящей: `LazyColumn` на повторном
     * ключе падает.
     */
    @Test
    fun `ключи реплик не повторяются после отмены удаления`() = runTest(dispatcher) {
        val entry = harness.addEntry("Яйцо", grams = 120, meal = n7.kcalai.model.MealType.BREAKFAST)
        val vm = screen()

        vm.onDeleteEntry(entry)
        advanceUntilIdle()
        vm.type("вес 82,4")
        vm.onLogWeight()
        advanceUntilIdle()
        vm.onUndoDelete(vm.state.value.feed.filterIsInstance<FeedItem.Deleted>().single())
        advanceUntilIdle()
        vm.type("вес 82,2")
        vm.onLogWeight()
        advanceUntilIdle()

        val keys = vm.state.value.feed.map { it.key }
        assertEquals(keys.distinct(), keys)
    }

    // --- ✓ на клавиатуре --------------------------------------------------------------------

    @Test
    fun `✓ на клавиатуре записывает вес, как тап по чипсу`() = runTest(dispatcher) {
        val vm = screen()

        vm.type("вес 82,4")
        advanceUntilIdle()
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(82_400, harness.bodyMetricDao.weights[harness.today])
        assertEquals("", vm.state.value.input)
    }

    /** После скана человек дописывает вес и жмёт ✓ — это и есть «добавить». */
    @Test
    fun `✓ на клавиатуре добавляет отсканированный товар с набранным весом`() = runTest(dispatcher) {
        harness.userFoodDao.add(barcode = GTIN, name = "Сыр", kcal100 = 350)
        val vm = screen()

        vm.onScanned(GTIN)
        advanceUntilIdle()
        vm.type(vm.state.value.input + "50")
        advanceUntilIdle()
        vm.onSubmit()
        advanceUntilIdle()

        val added = harness.diaryDao.day(harness.today).single()
        assertEquals("Сыр", added.displayName)
        assertEquals(50, added.grams)
        assertEquals("", vm.state.value.input)
    }

    /** Выдача поиска — это выбор, и делать его за человека ✓ не вправе. */
    @Test
    fun `✓ на клавиатуре не выбирает из выдачи поиска за человека`() = runTest(dispatcher) {
        val vm = screen()

        vm.type("гречка 200")
        advanceUntilIdle()
        vm.onSubmit()
        advanceUntilIdle()

        assertTrue(harness.diaryDao.day(harness.today).isEmpty())
        assertEquals("гречка 200", vm.state.value.input)
    }

    // --- «Ничего не нашлось» ----------------------------------------------------------------

    /**
     * «200» — это начало «200 г гречки», а не блюдо, и случайная точка — тоже
     * не блюдо. Отвечать на них «ничего не нашлось» значит перебивать человека
     * на полуслове.
     */
    @Test
    fun `строка без единой буквы — ещё не блюдо, и «не нашлось» не появляется`() = runTest(dispatcher) {
        val vm = screen()

        listOf("200", ".", "2,5").forEach { text ->
            vm.type(text)
            advanceUntilIdle()
            assertTrue(vm.state.value.feed.none { it is FeedItem.NotFound }, "на «$text»")
        }

        vm.type("кюфта")
        advanceUntilIdle()
        assertTrue(vm.state.value.feed.any { it is FeedItem.NotFound })
    }

    private companion object {
        const val GTIN = "4607001771234"
    }
}
