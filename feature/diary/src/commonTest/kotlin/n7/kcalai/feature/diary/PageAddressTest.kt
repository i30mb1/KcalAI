package n7.kcalai.feature.diary

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlin.test.AfterTest
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.yield
import n7.kcalai.database.PeerOfferEntity
import n7.kcalai.nearby.NearbyDiscovery
import n7.kcalai.nearby.OfferInbox
import n7.kcalai.nearby.OfferReceiver
import n7.kcalai.nearby.Peer
import n7.kcalai.nearby.pageUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Адрес страницы дневника — тот же сервер, что принимает соседей: есть, пока он поднят
 * и телефон в Wi-Fi, и пропадает вместе с ним.
 */
class PageAddressTest {

    private class Discovery(private val local: String?) : NearbyDiscovery {
        val advertised = mutableListOf<String>()

        override suspend fun advertise(name: String, port: Int) {
            advertised += name
        }

        override fun peers(): Flow<List<Peer>> = flow { awaitCancellation() }
        override fun localAddress(): String? = local
        override fun stop() = Unit
    }

    /** Сервер живёт в области приложения, а не экрана; в тесте — в своей, и гаснет вместе с тестом. */
    private val lanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stopLan() = lanScope.cancel()

    private object NoOffers : OfferInbox {
        override suspend fun waiting(): Int = 0
        override suspend fun receive(offer: PeerOfferEntity) = Unit
    }

    @Test
    fun `пока сервер поднят — адрес есть, ушёл экран — адреса нет`() = runTest {
        val lan = NearbyPeersOverLan(Discovery("192.168.1.5"), OfferReceiver(NoOffers), myName = MutableStateFlow("Женя"), scope = lanScope)

        // Сокет настоящий: ждём его на настоящих потоках, а не на виртуальном времени теста.
        val address = withContext(Dispatchers.Default) {
            val running = launch { lan.nearby().collect {} }
            lan.address().filterNotNull().first().also { running.cancelAndJoin() }
        }

        assertTrue(address.startsWith("http://192.168.1.5:"), address)
        // Без запаса страница гаснет вместе с экраном — сразу, как только сервер остановится.
        withContext(Dispatchers.Default) { withTimeout(5_000) { lan.address().first { it == null } } }
    }

    /**
     * Ушёл экран — страница держится запас: мельком заблокировал телефон — она не заметит.
     * Запас вышел — страница гаснет: убрал телефон надолго — связь честно закрывается.
     */
    @Test
    fun `ушёл экран — страница живёт запас и гаснет`() = runTest {
        val lan = NearbyPeersOverLan(
            Discovery("192.168.1.5"),
            OfferReceiver(NoOffers),
            myName = MutableStateFlow("Женя"),
            scope = lanScope,
            grace = MutableStateFlow(GRACE),
        )

        withContext(Dispatchers.Default) {
            val screen = launch { lan.nearby().collect {} }
            lan.address().filterNotNull().first()
            screen.cancelAndJoin()

            delay(GRACE / 3)
            assertTrue(lan.address().first() != null, "запас ещё не вышел — страница жива")

            withTimeout(5_000) { lan.address().first { it == null } }
        }
    }

    /**
     * Имя нужно соседям — им видно, кто пишет. Странице оно не нужно: свой дневник
     * открывают и без него, а после «Не скажу» Яйцо имя больше не спрашивает.
     */
    @Test
    fun `без имени страница поднимается, но себя соседям не объявляет`() = runTest {
        val discovery = Discovery("192.168.1.5")
        val lan = NearbyPeersOverLan(discovery, OfferReceiver(NoOffers), myName = MutableStateFlow(null), scope = lanScope)

        val address = withContext(Dispatchers.Default) {
            val running = launch { lan.nearby().collect {} }
            lan.address().filterNotNull().first().also { running.cancelAndJoin() }
        }

        assertTrue(address.startsWith("http://192.168.1.5:"), address)
        assertTrue(discovery.advertised.isEmpty(), "без имени соседям объявлять нечего")
    }

    /**
     * Сменили имя (в том числе со страницы) — соседям объявляемся заново, а сервер стоит:
     * перезапуск оборвал бы страницу, с которой имя и меняли.
     */
    @Test
    fun `смена имени сервер не перезапускает`() = runTest {
        val discovery = Discovery("192.168.1.5")
        val name = MutableStateFlow<String?>("Женя")
        val lan = NearbyPeersOverLan(discovery, OfferReceiver(NoOffers), myName = name, scope = lanScope)

        val (before, after) = withContext(Dispatchers.Default) {
            val running = launch { lan.nearby().collect {} }
            val first = lan.address().filterNotNull().first()
            // Адрес поднимается раньше объявления: ждём первое имя, иначе сменим его до того, как оно прозвучало.
            while (discovery.advertised.isEmpty()) kotlinx.coroutines.delay(10)
            name.value = "Аня"
            while (discovery.advertised.size < 2) kotlinx.coroutines.delay(10)
            val second = lan.address().first()
            running.cancelAndJoin()
            first to second
        }

        assertEquals(before, after, "порт тот же — сервер не перезапускался")
        assertEquals(listOf("Женя", "Аня"), discovery.advertised)
    }

    /** Не в Wi-Fi — адреса, по которому браузер достучался бы, нет: молчим, а не врём. */
    @Test
    fun `не в Wi-Fi адреса нет`() = runTest {
        val lan = NearbyPeersOverLan(Discovery(null), OfferReceiver(NoOffers), myName = MutableStateFlow("Женя"), scope = lanScope)

        val running = launch { lan.nearby().collect {} }
        yield()
        val address = lan.address().first()
        running.cancel()

        assertNull(address)
    }

    private companion object {
        val GRACE = 600.milliseconds
    }

    @Test
    fun `адрес страницы по IP и порту`() {
        assertEquals("http://192.168.1.5:47800/", pageUrl("192.168.1.5", 47800))
        assertEquals("http://[fe80::1]:47800/", pageUrl("fe80::1", 47800), "IPv6 в адресе — в квадратных скобках")
    }
}
