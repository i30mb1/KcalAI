package n7.kcalai.feature.diary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import n7.kcalai.model.Nutriments
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Человек отправляет еду соседу, а не себе.
 *
 * Главное здесь — что чужая еда не попадает в свой дневник: отправитель её
 * не ел. Поэтому режим отправки виден в подсказке строки ввода, а не угадывается.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendToPeerTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = DiaryHarness()
    private val peers = FakeNearbyPeers()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.screen(): DiaryViewModel =
        harness.viewModel(dispatcher, peers = peers).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

    @Test
    fun `сосед рядом — Зёрнышко зовёт отправить`() = runTest(dispatcher) {
        peers.nearby.value = listOf(NearbyPerson("a", "Аня"))

        val vm = screen()

        assertTrue(vm.state.value.feed.any { it is FeedItem.Nearby })
    }

    @Test
    fun `соседей нет — и звать некого`() = runTest(dispatcher) {
        val vm = screen()

        assertTrue(vm.state.value.feed.none { it is FeedItem.Nearby })
    }

    /** Режим виден: подсказка в поле говорит, кому пишут. Иначе еда уйдёт не туда. */
    @Test
    fun `в режиме отправки видно кому пишут`() = runTest(dispatcher) {
        peers.nearby.value = listOf(NearbyPerson("a", "Аня"))
        val vm = screen()

        vm.onSendToPeer("a")
        advanceUntilIdle()

        assertEquals("Аня", vm.state.value.sendingTo)
    }

    @Test
    fun `набранное уходит соседу а не в свой дневник`() = runTest(dispatcher) {
        peers.nearby.value = listOf(NearbyPerson("a", "Аня"))
        val vm = screen()
        vm.onSendToPeer("a")
        advanceUntilIdle()

        vm.onPickPrediction(harness.kefir)
        advanceUntilIdle()

        assertEquals(1, peers.sent.size, "предложение обязано уйти соседу")
        assertEquals("a" to "Кефир 1%", peers.sent.single())
        assertTrue(harness.diaryDao.day(harness.today).isEmpty(), "себе чужую еду не пишем")
    }

    @Test
    fun `после отправки режим снимается`() = runTest(dispatcher) {
        peers.nearby.value = listOf(NearbyPerson("a", "Аня"))
        val vm = screen()
        vm.onSendToPeer("a")
        advanceUntilIdle()

        vm.onPickPrediction(harness.kefir)
        advanceUntilIdle()

        assertEquals(null, vm.state.value.sendingTo, "дальше человек пишет снова себе")
    }

    /** Передумал — и пишет себе: выйти из режима можно, не отправляя. */
    @Test
    fun `из режима отправки можно выйти`() = runTest(dispatcher) {
        peers.nearby.value = listOf(NearbyPerson("a", "Аня"))
        val vm = screen()
        vm.onSendToPeer("a")
        advanceUntilIdle()

        vm.onCancelSendToPeer()
        advanceUntilIdle()

        assertEquals(null, vm.state.value.sendingTo)
    }

    /** Сосед ушёл из сети — человеку говорят об этом, а не молчат. */
    @Test
    fun `молчащий сосед не остаётся без ответа`() = runTest(dispatcher) {
        peers.nearby.value = listOf(NearbyPerson("a", "Аня"))
        peers.reachable = false
        val vm = screen()
        vm.onSendToPeer("a")
        advanceUntilIdle()

        vm.onPickPrediction(harness.kefir)
        advanceUntilIdle()

        assertTrue(vm.state.value.feed.any { it is FeedItem.Reply }, "о неудаче сказано в ленте")
    }

    /**
     * Поиск соседей молчит, пока никого не нашёл, — так ведёт себя Bonjour.
     *
     * Лента обязана работать и в этом случае: `combine` не отдаёт состояние,
     * пока не отозвался каждый поток, и молчащий поиск однажды оставил экран
     * пустым целиком — при живом приложении.
     */
    @Test
    fun `экран работает пока поиск соседей молчит`() = runTest(dispatcher) {
        val silent = object : NearbyPeers {
            override fun nearby(): Flow<List<NearbyPerson>> = flow { awaitCancellation() }
            override suspend fun send(id: String, food: String, grams: Int, nutriments: Nutriments) = false
        }

        val vm = harness.viewModel(dispatcher, peers = silent).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

        assertTrue(vm.state.value.loaded, "лента не дождалась молчащего поиска и осталась пустой")
    }

    private class FakeNearbyPeers : NearbyPeers {
        val nearby = MutableStateFlow<List<NearbyPerson>>(emptyList())
        val sent = mutableListOf<Pair<String, String>>()
        var reachable = true

        override fun nearby(): Flow<List<NearbyPerson>> = nearby

        override suspend fun send(id: String, food: String, grams: Int, nutriments: Nutriments): Boolean {
            if (!reachable) return false
            sent += id to food
            return true
        }
    }
}
