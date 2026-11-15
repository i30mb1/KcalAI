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
import n7.kcalai.database.Choice
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.PeerOfferEntity
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сосед предлагает запись, а человек подтверждает её тапом.
 *
 * До подтверждения в дневнике нет ничего: предложение живёт сообщением в ленте.
 * Ответ его не убирает — лента переписка, кнопки лишь гаснут с отметкой выбора.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerOfferTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = DiaryHarness()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.screen(): DiaryViewModel =
        harness.viewModel(dispatcher, peerOffers = harness.peerOfferDao).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

    private suspend fun offer(food: String, grams: Int = 200, from: String = "Аня"): Long =
        harness.peerOfferDao.insert(
            PeerOfferEntity(
                fromName = from,
                displayName = food,
                grams = grams,
                kcal100 = 110,
                prot100 = 420,
                fat100 = 110,
                carb100 = 2_130,
                fiber100 = 280,
                dateEpochDay = harness.today,
                createdAt = harness.clock.now().toEpochMilliseconds() - 60_000L,
            )
        )

    private fun DiaryViewModel.offers() = state.value.feed.filterIsInstance<FeedItem.Offer>()

    @Test
    fun `предложение соседа стоит в ленте и ждёт ответа`() = runTest(dispatcher) {
        offer("Гречка отварная")

        val vm = screen()

        val shown = vm.offers().single()
        assertEquals("Аня", shown.fromName)
        assertEquals("Гречка отварная", shown.food)
        assertEquals(200, shown.grams)
        assertNull(shown.choice, "ответа ещё нет — кнопки живые")
        assertTrue(harness.diaryDao.day(harness.today).isEmpty(), "до подтверждения в дневнике пусто")
    }

    @Test
    fun `подтверждение кладёт запись в дневник`() = runTest(dispatcher) {
        val id = offer("Гречка отварная")
        val vm = screen()

        vm.onAnswerOffer(id, yes = true)
        advanceUntilIdle()

        val entry = harness.diaryDao.day(harness.today).single()
        assertEquals("Гречка отварная", entry.displayName)
        assertEquals(200, entry.grams)
        assertEquals(EntrySource.SHARED, entry.source)
        assertEquals(280, entry.fiber100)
        assertNull(entry.foodRef, "ссылка на продукт соседа у нас ничего не значит")
    }

    /** Лента — переписка: отвеченное остаётся на месте с погасшими кнопками. */
    @Test
    fun `отвеченное предложение остаётся в ленте`() = runTest(dispatcher) {
        val id = offer("Гречка отварная")
        val vm = screen()

        vm.onAnswerOffer(id, yes = true)
        advanceUntilIdle()

        assertEquals(Choice.YES, vm.offers().single().choice)
    }

    @Test
    fun `отказ не пишет в дневник ничего`() = runTest(dispatcher) {
        val id = offer("Гречка отварная")
        val vm = screen()

        vm.onAnswerOffer(id, yes = false)
        advanceUntilIdle()

        assertTrue(harness.diaryDao.day(harness.today).isEmpty())
        assertEquals(Choice.NO, vm.offers().single().choice)
    }

    /** Повторный тап ничего не запускает — проверка в логике, не только в виде. */
    @Test
    fun `второй тап второй раз не записывает`() = runTest(dispatcher) {
        val id = offer("Гречка отварная")
        val vm = screen()

        vm.onAnswerOffer(id, yes = true)
        advanceUntilIdle()
        vm.onAnswerOffer(id, yes = true)
        advanceUntilIdle()

        assertEquals(1, harness.diaryDao.day(harness.today).size, "запись обязана быть ровно одна")
    }

    /** Ответ на одно предложение не трогает другое: их приходит несколько разом. */
    @Test
    fun `ответ на одно предложение не отвечает за другое`() = runTest(dispatcher) {
        val buckwheat = offer("Гречка")
        offer("Творог", grams = 180)
        val vm = screen()

        vm.onAnswerOffer(buckwheat, yes = true)
        advanceUntilIdle()

        val offers = vm.offers()
        assertEquals(Choice.YES, offers.single { it.food == "Гречка" }.choice)
        assertNull(offers.single { it.food == "Творог" }.choice, "творог остался ждать")
    }

    /**
     * Персонажи говорят по одному: пока предложение ждёт ответа, подсказки молчат.
     * Иначе остаток дня и предложение соседа заговорили бы разом.
     */
    @Test
    fun `пока предложение ждёт ответа перебор ждёт тоже — а после ответа встаёт`() = runTest(dispatcher) {
        harness.goalDao.upsert(DailyGoalEntity(harness.today, kcal = 50, prot = 0, fat = 0, carb = 0))
        harness.addEntry("Гречка отварная", grams = 200, meal = MealType.BREAKFAST)
        val id = offer("Творог", grams = 180)
        val vm = screen()

        assertTrue(vm.state.value.feed.none { it is FeedItem.Over }, "перебор ждёт ответа на предложение")

        vm.onAnswerOffer(id, yes = false)
        advanceUntilIdle()

        assertTrue(vm.state.value.feed.any { it is FeedItem.Over })
    }

    /** Без шва фича выключена целиком: предложений нет, и спрашивать некого. */
    @Test
    fun `без соседей лента прежняя`() = runTest(dispatcher) {
        offer("Гречка отварная")

        val vm = harness.viewModel(dispatcher, peerOffers = null).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

        assertTrue(vm.state.value.feed.none { it is FeedItem.Offer })
    }
}
