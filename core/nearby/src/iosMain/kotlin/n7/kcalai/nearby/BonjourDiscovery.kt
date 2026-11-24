package n7.kcalai.nearby

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import n7.kcalai.model.Log
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.INET_ADDRSTRLEN
import platform.posix.inet_ntop
import platform.posix.sockaddr_in

/**
 * Поиск соседей через Bonjour.
 *
 * `NSNetService`, а не MultipeerConnectivity: тот в iOS 27 помечен
 * `API_DEPRECATED("Use Network Framework instead")`. И не `nw_browser`:
 * тот отдаёт результаты блоком-перечислителем, который Kotlin/Native не принимает,
 * а у `NSNetService` делегаты — обычные протоколы, доступные из Kotlin напрямую.
 * Обе — готовые platform-библиотеки, cinterop настраивать не нужно.
 *
 * Bonjour — та же DNS-SD, что `NsdManager` на Android: телефоны видят друг друга
 * без мостов.
 *
 * Важно: при первом обращении iOS спрашивает разрешение на локальную сеть,
 * а при отказе браузер **молча** не находит никого — узнать статус разрешения
 * публичным API нельзя. Поэтому у экрана обязан быть таймаут и реплика персонажа,
 * а не вечное ожидание. И в Info.plist обязаны стоять `NSLocalNetworkUsageDescription`
 * и `NSBonjourServices` с тем же типом службы — без них не работает ничего.
 */
@OptIn(ExperimentalForeignApi::class)
class BonjourDiscovery : NearbyDiscovery {

    /** Держим ссылку: NSNetService живёт, только пока на него кто-то ссылается. */
    private var published: NSNetService? = null

    private var advertisedName: String? = null

    override suspend fun advertise(name: String, port: Int) {
        stop()
        advertisedName = name
        // Домен пустой — «по умолчанию», то есть local.
        val service = NSNetService(
            domain = "",
            type = "$OFFER_SERVICE_TYPE.",
            name = name,
            port = port.convert(),
        )
        published = service
        service.publish()
    }

    override fun peers(): Flow<List<Peer>> = callbackFlow {
        val found = mutableMapOf<String, Peer>()
        // Разрешаемые держим в поле: без ссылки NSNetService умрёт раньше делегата.
        val resolving = mutableListOf<NSNetService>()

        val resolver = object : NSObject(), NSNetServiceDelegateProtocol {
            override fun netServiceDidResolveAddress(sender: NSNetService) {
                val host = sender.hostName ?: return
                found[sender.name] = Peer(
                    name = sender.name,
                    // Bonjour отдаёт имя с точкой на конце — её в адрес не кладут.
                    host = host.removeSuffix("."),
                    port = sender.port.toInt(),
                )
                trySend(found.values.toList())
                resolving.remove(sender)
            }

            override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
                // Сосед ушёл, пока мы его разрешали — обычное дело.
                Log.i(TAG, "сосед не отозвался: ${sender.name}")
                resolving.remove(sender)
            }
        }

        val browser = NSNetServiceBrowser()
        browser.setDelegate(
            object : NSObject(), NSNetServiceBrowserDelegateProtocol {
                // Найти и потерять в Objective-C разные селекторы, а в Kotlin
                // сигнатура одна — отсюда отметка.
                @ObjCSignatureOverride
                override fun netServiceBrowser(
                    browser: NSNetServiceBrowser,
                    didFindService: NSNetService,
                    moreComing: Boolean,
                ) {
                    // Своё же объявление в выдаче не нужно: предлагать еду себе незачем.
                    if (didFindService.name == advertisedName) return
                    didFindService.setDelegate(resolver)
                    resolving += didFindService
                    didFindService.resolveWithTimeout(RESOLVE_TIMEOUT_SECONDS)
                }

                @ObjCSignatureOverride
                override fun netServiceBrowser(
                    browser: NSNetServiceBrowser,
                    didRemoveService: NSNetService,
                    moreComing: Boolean,
                ) {
                    found.remove(didRemoveService.name)
                    trySend(found.values.toList())
                }

                override fun netServiceBrowser(
                    browser: NSNetServiceBrowser,
                    didNotSearch: Map<Any?, *>,
                ) {
                    // Чаще всего это отказ в доступе к локальной сети.
                    Log.w(TAG, "поиск соседей не начался: $didNotSearch")
                    close()
                }
            }
        )
        browser.searchForServicesOfType("$OFFER_SERVICE_TYPE.", inDomain = "")

        awaitClose {
            browser.stop()
            browser.setDelegate(null)
        }
    }

    /** Адрес на `en0` — это Wi-Fi у iPhone; сотовая связь живёт на других интерфейсах. */
    override fun localAddress(): String? = memScoped {
        val list = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(list.ptr) != 0) return null
        try {
            var cursor = list.value
            while (cursor != null) {
                val entry = cursor.pointed
                val address = entry.ifa_addr
                if (entry.ifa_name?.toKString() == WIFI_INTERFACE && address != null &&
                    address.pointed.sa_family.toInt() == AF_INET
                ) {
                    val text = allocArray<ByteVar>(INET_ADDRSTRLEN)
                    inet_ntop(AF_INET, address.reinterpret<sockaddr_in>().pointed.sin_addr.ptr, text, INET_ADDRSTRLEN.convert())
                    return text.toKString()
                }
                cursor = entry.ifa_next
            }
            null
        } finally {
            freeifaddrs(list.value)
        }
    }

    override fun stop() {
        published?.stop()
        published = null
        advertisedName = null
    }

    private companion object {
        const val TAG = "BonjourDiscovery"

        const val WIFI_INTERFACE = "en0"

        /** Дольше ждать соседа незачем: он либо рядом, либо уже ушёл. */
        const val RESOLVE_TIMEOUT_SECONDS = 5.0
    }
}
