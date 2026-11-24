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
import n7.kcalai.model.MealType
import n7.kcalai.webapi.ToPage
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WebEntry
import n7.kcalai.webapi.WebPeer
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
                owner = "Женя",
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
        assertEquals(listOf(Triple("гречка", 1, 180)), diary.added)
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
        admit("token-1")
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

private class FakeWebDiary : WebDiary {
    val entries = MutableStateFlow<List<WebEntry>>(emptyList())
    val found = listOf(WebSuggestion("Гречка отварная", 200, 220), WebSuggestion("Гречка ядрица", 60, 188))
    val added = mutableListOf<Triple<String, Int, Int>>()
    val regrammed = mutableListOf<Pair<Long, Int>>()
    val deleted = mutableListOf<Long>()

    override fun today() = kotlinx.coroutines.flow.combine(entries, MutableStateFlow(2_100)) { list, goal -> WebToday(list, goal) }

    override suspend fun suggest(query: String): List<WebSuggestion> = found

    override suspend fun add(query: String, index: Int, grams: Int) {
        added += Triple(query, index, grams)
    }

    override suspend fun setGrams(entryId: Long, grams: Int) {
        regrammed += entryId to grams
    }

    override suspend fun delete(entryId: Long) {
        deleted += entryId
    }
}
