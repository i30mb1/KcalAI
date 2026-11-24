package n7.kcalai.feature.diary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import n7.kcalai.database.Choice
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.WebClientEntity
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Браузер у страницы дневника: просится — в ленте встаёт вопрос Яйца, и пускает
 * только тап. Пока пущенный браузер на связи, экран телефона не гаснет: иначе
 * страница отвалится посреди ввода.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = DiaryHarness()
    private val requests = FakeWebClientDao()
    private val online = MutableStateFlow(0)
    private val peers = PagePeers()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.screen(): DiaryViewModel =
        harness.viewModel(dispatcher, browsers = Browsers(requests, online), peers = peers).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

    private suspend fun asks(label: String = "Chrome на Windows", code: String = "4821"): Long =
        requests.insert(
            WebClientEntity(
                token = "t-$code",
                code = code,
                label = label,
                dateEpochDay = harness.today,
                createdAt = harness.clock.now().toEpochMilliseconds() - 60_000L,
            )
        )

    private fun DiaryViewModel.browsers() = state.value.feed.filterIsInstance<FeedItem.Browser>()

    @Test
    fun `просьба браузера стоит в ленте вопросом Яйца с кодом`() = runTest(dispatcher) {
        asks()

        val vm = screen()

        val shown = vm.browsers().single()
        assertEquals("Chrome на Windows", shown.label)
        assertEquals("4821", shown.code)
        assertNull(shown.choice, "ответа нет — кнопки живые")
        assertEquals(Mascot.EGG, speakerOf(shown))
    }

    @Test
    fun `пустить — один раз, второй тап ответ не меняет`() = runTest(dispatcher) {
        val id = asks()
        val vm = screen()

        vm.onAnswerBrowser(id, yes = true)
        advanceUntilIdle()
        vm.onAnswerBrowser(id, yes = false)
        advanceUntilIdle()

        assertEquals(Choice.YES, requests.find("t-4821")?.choice)
        assertEquals(Choice.YES, vm.browsers().single().choice, "просьба остаётся в переписке с отметкой ответа")
    }

    /** Персонажи говорят по одному: пока просьба ждёт ответа, подсказки молчат. */
    @Test
    fun `пока просьба ждёт ответа перебор молчит`() = runTest(dispatcher) {
        harness.goalDao.upsert(DailyGoalEntity(harness.today, kcal = 50, prot = 0, fat = 0, carb = 0))
        harness.addEntry("Гречка отварная", grams = 200, meal = MealType.BREAKFAST)
        val id = asks()
        val vm = screen()

        assertTrue(vm.state.value.feed.none { it is FeedItem.Over })

        vm.onAnswerBrowser(id, yes = false)
        advanceUntilIdle()

        assertTrue(vm.state.value.feed.any { it is FeedItem.Over })
    }

    @Test
    fun `пока пущенный браузер на связи — экран не гаснет`() = runTest(dispatcher) {
        val vm = screen()
        assertFalse(vm.state.value.keepScreenOn)

        online.value = 1
        advanceUntilIdle()
        assertTrue(vm.state.value.keepScreenOn)

        online.value = 0
        advanceUntilIdle()
        assertFalse(vm.state.value.keepScreenOn)
    }

    /** Захотел освежить в памяти — спросил словом, и Зёрнышко назвал адрес, а поиск еды молчит. */
    @Test
    fun `адрес страницы — по просьбе в строке`() = runTest(dispatcher) {
        harness.addEntry("Гречка отварная", grams = 200, meal = MealType.BREAKFAST, daysAgo = 1)
        val vm = screen()

        vm.onInputChange("компьютер")
        advanceUntilIdle()
        assertTrue(vm.state.value.feed.none { it is FeedItem.NotFound }, "«компьютер» — не еда, «не нашлось» тут лишнее")

        assertTrue(vm.onSubmit())
        advanceUntilIdle()

        val said = vm.state.value.feed.filterIsInstance<FeedItem.Said>().last { PAGE in it.text }
        assertEquals(Mascot.GRAIN, said.mascot)
        assertEquals(2, harness.mascotDao.messages.value.count { it.topic == Topic.WEB_ADDRESS.name }, "само рассказал и по просьбе повторил")
        assertTrue(PAGE in said.text, said.text)
        assertEquals("", vm.state.value.input)
    }

    @Test
    fun `о странице Зёрнышко рассказывает само — один раз`() = runTest(dispatcher) {
        harness.addEntry("Гречка отварная", grams = 200, meal = MealType.BREAKFAST, daysAgo = 1)

        screen()
        advanceUntilIdle()
        screen()
        advanceUntilIdle()

        assertEquals(1, harness.mascotDao.messages.value.count { it.topic == Topic.WEB_ADDRESS.name })
    }

    /** Ссылку удобно скинуть себе в мессенджер и открыть на компьютере: у адреса — «Поделиться». */
    @Test
    fun `у адреса страницы — ссылка, которой можно поделиться`() = runTest(dispatcher) {
        harness.addEntry("Гречка отварная", grams = 200, meal = MealType.BREAKFAST, daysAgo = 1)
        val vm = screen()
        advanceUntilIdle()

        val said = vm.state.value.feed.filterIsInstance<FeedItem.Said>()
        assertEquals(PAGE, said.single { PAGE in it.text }.share)
        assertTrue(said.filterNot { PAGE in it.text }.all { it.share == null }, "делиться — только адресом страницы")
    }

    /** Знакомство учит, как вернуть адрес: иначе его не найти, когда он понадобится. */
    @Test
    fun `знакомство со страницей говорит, как спросить адрес снова`() = runTest(dispatcher) {
        harness.addEntry("Гречка отварная", grams = 200, meal = MealType.BREAKFAST, daysAgo = 1)

        screen()
        advanceUntilIdle()

        val intro = harness.mascotDao.messages.value.single { it.topic == Topic.WEB_ADDRESS.name }
        assertTrue("«компьютер»" in intro.text, intro.text)
    }

    /** Адреса нет — телефон не в Wi-Fi. Это не еда, и «не нашлось» тут сбивает с толку: Зёрнышко объясняет. */
    @Test
    fun `адреса нет — Зёрнышко объясняет, что сделать`() = runTest(dispatcher) {
        harness.addEntry("Гречка отварная", grams = 200, meal = MealType.BREAKFAST, daysAgo = 1)
        val vm = harness.viewModel(dispatcher, browsers = Browsers(requests, online), peers = NoWifi()).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

        vm.onInputChange("компьютер")
        advanceUntilIdle()
        assertTrue(vm.state.value.feed.none { it is FeedItem.NotFound })

        assertTrue(vm.onSubmit())
        advanceUntilIdle()

        val said = harness.mascotDao.messages.value.single { it.topic == Topic.WEB_NO_WIFI.name }
        assertEquals(Mascot.GRAIN, said.mascot)
        assertTrue("Wi-Fi" in said.text, said.text)
        assertEquals("", vm.state.value.input)
    }

    @Test
    fun `просьбы о странице — словами, а не еда`() {
        listOf("компьютер", "Ноутбук", "с компьютера", "адрес?", "сайт", "браузер").forEach {
            assertTrue(asksForPage(it), it)
        }
        listOf("гречка", "компот", "адрес гречки", "").forEach {
            assertFalse(asksForPage(it), it)
        }
    }

    private class PagePeers : NearbyPeers {
        override fun nearby(): Flow<List<NearbyPerson>> = flowOf(emptyList())
        override fun address(): Flow<String?> = flowOf(PAGE)
        override suspend fun send(id: String, food: String, grams: Int, nutriments: Nutriments) = false
    }

    private class NoWifi : NearbyPeers {
        override fun nearby(): Flow<List<NearbyPerson>> = flowOf(emptyList())
        override suspend fun send(id: String, food: String, grams: Int, nutriments: Nutriments) = false
    }

    private companion object {
        const val PAGE = "http://192.168.1.5:47800/"
    }
}
