package n7.kcalai.webhost

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import n7.kcalai.database.Choice
import n7.kcalai.model.Mascot
import n7.kcalai.model.PageGrace
import n7.kcalai.model.TreadmillSample
import n7.kcalai.model.MealType
import n7.kcalai.webapi.ToPage
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WebEntry
import n7.kcalai.webapi.WebDaySummary
import n7.kcalai.webapi.WebMacros
import n7.kcalai.webapi.WebPeer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import n7.kcalai.webapi.WebSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Браузер на странице телефона: пока человек не пустил его тапом, он не видит и не пишет ничего.
 *
 * Сессия проверяется без сокета: на вход — сообщения страницы, на выход — что ей сказано.
 * Сокет и разбор текста проверены отдельно. Сессии живут в `backgroundScope`, а его
 * `advanceUntilIdle` не гоняет, поэтому шаг — `runCurrent`: задержек здесь нет.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebSessionTest {

    private val clients = FakeWebClientDao()
    private val diary = FakeWebDiary()
    private val peers = MutableStateFlow(listOf(WebPeer("Аня", "http://192.168.1.7:47800/")))
    private val presence = WebPresence()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-11-24T09:00:00Z")
    }
    private var tokens = 0

    private inner class Browser(scope: TestScope) {
        val incoming = Channel<ToPhone>(Channel.UNLIMITED)
        val heard = mutableListOf<ToPage>()

        init {
            val session = WebSession(
                clients = clients,
                diary = diary,
                owner = flowOf("Женя"),
                grace = flowOf(PageGrace.MIN_15),
                peers = peers,
                label = "Chrome на Windows",
                presence = presence,
                clock = clock,
                zone = TimeZone.UTC,
                newToken = { "token-${++tokens}" },
                newCode = { "4821" },
            )
            scope.backgroundScope.launch { session.run(incoming.consumeAsFlow()) { heard += it } }
        }

        fun say(message: ToPhone) {
            incoming.trySend(message)
        }

        inline fun <reified T : ToPage> last(): T? = heard.filterIsInstance<T>().lastOrNull()
    }

    private suspend fun admit(token: String) {
        clients.answer(clients.find(token)!!.id, Choice.YES)
    }

    @Test
    fun `новый браузер получает код и ждёт, а в ленте встаёт просьба`() = runTest {
        val browser = Browser(this)

        browser.say(ToPhone.Hello(token = null))
        runCurrent()

        assertEquals(ToPage.Pairing(token = "token-1", code = "4821"), browser.heard.single())
        val asked = clients.find("token-1")
        assertNotNull(asked, "просьба обязана лечь в базу — оттуда её возьмёт лента")
        assertEquals("Chrome на Windows", asked.label)
        assertEquals("4821", asked.code)
        assertNull(asked.choice)
    }

    @Test
    fun `пущенный браузер видит дневник`() = runTest {
        diary.entries.value = listOf(buckwheat)
        val browser = Browser(this)
        browser.say(ToPhone.Hello(token = null))
        runCurrent()

        admit("token-1")
        runCurrent()

        val day = assertNotNull(browser.last<ToPage.Day>()).day
        assertEquals("Женя", day.owner)
        assertEquals(listOf(buckwheat), day.entries)
        assertEquals(2_100, day.goalKcal)
        assertEquals(peers.value, day.peers)
    }

    @Test
    fun `на отказ браузер слышит нет и дневника не видит`() = runTest {
        val browser = Browser(this)
        browser.say(ToPhone.Hello(token = null))
        runCurrent()

        clients.answer(clients.find("token-1")!!.id, Choice.NO)
        runCurrent()

        assertIs<ToPage.Denied>(browser.heard.last())
        assertNull(browser.last<ToPage.Day>())
    }

    /** Проверка в логике, а не на странице: страницу можно и подделать. */
    @Test
    fun `до ответа браузер ничего не ищет и не пишет`() = runTest {
        val browser = Browser(this)
        browser.say(ToPhone.Hello(token = null))
        browser.say(ToPhone.Search("гречка"))
        browser.say(ToPhone.Add(query = "гречка", index = 0, grams = 200))
        browser.say(ToPhone.Delete(entryId = 7))
        runCurrent()

        assertTrue(diary.added.isEmpty())
        assertTrue(diary.deleted.isEmpty())
        assertNull(browser.last<ToPage.Suggestions>())
    }

    @Test
    fun `пущенного раньше браузер узнают без нового вопроса`() = runTest {
        Browser(this).say(ToPhone.Hello(token = null))
        runCurrent()
        admit("token-1")

        val again = Browser(this)
        again.say(ToPhone.Hello(token = "token-1"))
        runCurrent()

        assertNotNull(again.last<ToPage.Day>())
        assertNull(again.last<ToPage.Pairing>())
        assertEquals(1, clients.all().size, "второй просьбы нет")
    }

    @Test
    fun `подсказки и запись — тем же поиском, что в приложении`() = runTest {
        val browser = admitted()

        browser.say(ToPhone.Search("гречка"))
        runCurrent()
        assertEquals(ToPage.Suggestions("гречка", diary.found), browser.last<ToPage.Suggestions>())

        browser.say(ToPhone.Add(query = "гречка", index = 1, grams = 180))
        runCurrent()
        assertEquals(listOf(Added("гречка", 1, 180, diary.today, null)), diary.added)
    }

    @Test
    fun `правка граммов и удаление доходят до дневника`() = runTest {
        val browser = admitted()

        browser.say(ToPhone.SetGrams(entryId = 7, grams = 150))
        browser.say(ToPhone.Delete(entryId = 7))
        runCurrent()

        assertEquals(listOf(7L to 150), diary.regrammed)
        assertEquals(listOf(7L), diary.deleted)
    }

    @Test
    fun `дневник на странице обновляется сам`() = runTest {
        val browser = admitted()

        diary.entries.value = listOf(buckwheat)
        runCurrent()

        assertEquals(listOf(buckwheat), browser.last<ToPage.Day>()?.day?.entries)
    }

    @Test
    fun `открыть другой день — страница получает его`() = runTest {
        val browser = admitted()

        browser.say(ToPhone.Open(day = diary.today - 3))
        runCurrent()

        assertEquals(diary.today - 3, browser.last<ToPage.Day>()?.day?.date)
        assertEquals(diary.today, browser.last<ToPage.Day>()?.day?.today)
    }

    /** В будущее не заглядывают: записывать туда нечего. */
    @Test
    fun `будущий день не открывается`() = runTest {
        val browser = admitted()

        browser.say(ToPhone.Open(day = diary.today + 5))
        runCurrent()

        assertEquals(diary.today, browser.last<ToPage.Day>()?.day?.date)
    }

    @Test
    fun `запись — в открытый день и выбранный приём`() = runTest {
        val browser = admitted()
        browser.say(ToPhone.Open(day = diary.today - 1))
        browser.say(ToPhone.Add(query = "гречка", index = 0, grams = 200, meal = MealType.DINNER))
        runCurrent()

        assertEquals(listOf(Added("гречка", 0, 200, diary.today - 1, MealType.DINNER)), diary.added)
    }

    /** Дорожка подключена к телефону — её живые числа видны на странице, но только у сегодняшнего дня. */
    @Test
    fun `живые числа дорожки — в сегодняшнем дне`() = runTest {
        val browser = admitted()

        diary.live.value = TreadmillSample(speedKmh = 4.0, distanceM = 120, kcal = 6, elapsedS = 110)
        runCurrent()
        assertEquals(TreadmillSample(4.0, 120, 6, 110), browser.last<ToPage.Day>()?.day?.live)

        browser.say(ToPhone.Open(day = 20_780))
        runCurrent()
        assertEquals(null, browser.last<ToPage.Day>()?.day?.live)
    }

    @Test
    fun `корзинка со страницы скрывает реплику на телефоне`() = runTest {
        val browser = admitted()

        browser.say(ToPhone.Hide(messageId = 41))
        runCurrent()

        assertEquals(listOf(41L), diary.hidden)
    }

    @Test
    fun `цель, имя и запас со страницы доходят до телефона`() = runTest {
        val browser = admitted()

        browser.say(ToPhone.SetGoal(prot = 120, fat = 70, carb = 230))
        browser.say(ToPhone.SetName("Женя"))
        browser.say(ToPhone.SetGrace(PageGrace.MIN_60))
        runCurrent()

        assertEquals(listOf(WebMacros(120, 70, 230)), diary.goals)
        assertEquals(listOf("Женя"), diary.names)
        assertEquals(listOf(PageGrace.MIN_60), diary.graces)
        assertEquals(PageGrace.MIN_15, browser.last<ToPage.Day>()?.day?.grace, "выбранный запас виден в настройках")
    }

    @Test
    fun `цель шагов и рост со страницы доходят до телефона, рост виден в панели`() = runTest {
        val browser = admitted()

        browser.say(ToPhone.SetSteps(goal = 10_000, heightCm = 182))
        runCurrent()

        assertEquals(listOf<Pair<Int, Int?>>(10_000 to 182), diary.steps)
        assertEquals(176, browser.last<ToPage.Day>()?.day?.heightCm, "рост из профиля — в данных дня")
    }

    @Test
    fun `пущенные браузеры видны в настройках, свой отмечен`() = runTest {
        Browser(this).say(ToPhone.Hello(token = null))
        runCurrent()
        admit("token-1")
        val browser = admitted()

        val shown = assertNotNull(browser.last<ToPage.Day>()).day.browsers
        assertEquals(2, shown.size)
        assertEquals(listOf(false, true), shown.map { it.here })
    }

    /** Отозвать можно и чужой браузер: на компьютере, забытом в гостях, дневник больше не откроется. */
    @Test
    fun `отозванный браузер больше не видит дневник`() = runTest {
        val other = Browser(this)
        other.say(ToPhone.Hello(token = null))
        runCurrent()
        admit("token-1")
        runCurrent()
        val me = admitted()

        me.say(ToPhone.Revoke(browserId = clients.find("token-1")!!.id))
        runCurrent()
        val before = other.heard.size
        diary.entries.value = listOf(buckwheat)
        runCurrent()

        assertIs<ToPage.Denied>(other.heard.last(), "отозванному говорят, что не пускают")
        assertTrue(other.heard.drop(before).none { it is ToPage.Day }, "и новых записей он не видит")
    }

    @Test
    fun `вышедший браузер в следующий раз снова просится`() = runTest {
        val browser = admitted()

        browser.say(ToPhone.Leave)
        runCurrent()
        assertNotNull(clients.find("token-1")?.leftAt, "выход помечается, а не стирает просьбу из переписки")

        val again = Browser(this)
        again.say(ToPhone.Hello(token = "token-1"))
        runCurrent()
        assertEquals(ToPage.Pairing(token = "token-2", code = "4821"), again.last<ToPage.Pairing>())
    }

    /** Иначе любой в той же сети завалит ленту просьбами. */
    @Test
    fun `сверх предела просьб новых не спрашивают`() = runTest {
        repeat(WEB_WAITING_MAX) {
            Browser(this).say(ToPhone.Hello(token = null))
            runCurrent()
        }

        val extra = Browser(this)
        extra.say(ToPhone.Hello(token = null))
        runCurrent()

        assertIs<ToPage.Busy>(extra.heard.single())
        assertEquals(WEB_WAITING_MAX, clients.all().size)
    }

    /** Пока на странице пущенный браузер, телефону гаснуть нельзя — страница отвалится. */
    @Test
    fun `пущенный браузер на связи — телефон знает об этом`() = runTest {
        val browser = Browser(this)
        browser.say(ToPhone.Hello(token = null))
        runCurrent()
        assertEquals(0, presence.admitted.value, "ждущий браузер не в счёт")

        admit("token-1")
        runCurrent()
        assertEquals(1, presence.admitted.value)

        browser.incoming.close()
        runCurrent()
        assertEquals(0, presence.admitted.value, "ушёл со страницы — больше не держит экран")
    }

    private suspend fun TestScope.admitted(): Browser {
        val browser = Browser(this)
        browser.say(ToPhone.Hello(token = null))
        runCurrent()
        admit("token-${tokens}")
        runCurrent()
        return browser
    }

    private val buckwheat = WebEntry(
        id = 7,
        name = "Гречка отварная",
        grams = 200,
        meal = MealType.LUNCH,
        kcal = 220,
        protCg = 840,
        fatCg = 220,
        carbCg = 4_260,
    )
}

private data class Added(val query: String, val index: Int, val grams: Int, val day: Long, val meal: MealType?)

private class FakeWebDiary : WebDiary {
    val entries = MutableStateFlow<List<WebEntry>>(emptyList())
    val found = listOf(WebSuggestion("Гречка отварная", 200, 220, Mascot.GRAIN), WebSuggestion("Гречка ядрица", 60, 188, Mascot.GRAIN))
    val added = mutableListOf<Added>()
    val regrammed = mutableListOf<Pair<Long, Int>>()
    val deleted = mutableListOf<Long>()
    val goals = mutableListOf<WebMacros>()
    val names = mutableListOf<String>()
    val graces = mutableListOf<PageGrace>()
    val steps = mutableListOf<Pair<Int, Int?>>()
    override val live = MutableStateFlow<TreadmillSample?>(null)
    val hidden = mutableListOf<Long>()

    /** 2026-11-24. */
    override val today: Long = 20_781

    override fun day(date: Long) = entries.map { list -> WebDayContent(list.takeIf { date == today }.orEmpty(), 2_100, heightCm = 176) }

    override fun week(date: Long) = flowOf(emptyList<WebDaySummary>())

    override suspend fun suggest(query: String): List<WebSuggestion> = found

    override suspend fun add(query: String, index: Int, grams: Int, day: Long, meal: MealType?) {
        added += Added(query, index, grams, day, meal)
    }

    override suspend fun setGoal(goal: WebMacros) {
        goals += goal
    }

    override suspend fun setName(name: String) {
        names += name
    }

    override suspend fun setGrace(grace: PageGrace) {
        graces += grace
    }

    override suspend fun setSteps(goal: Int, heightCm: Int?) {
        steps += goal to heightCm
    }

    override suspend fun setGrams(entryId: Long, grams: Int) {
        regrammed += entryId to grams
    }

    override suspend fun delete(entryId: Long) {
        deleted += entryId
    }

    override suspend fun hideSaid(messageId: Long) {
        hidden += messageId
    }
}
