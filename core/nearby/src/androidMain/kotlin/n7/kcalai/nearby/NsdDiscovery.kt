package n7.kcalai.nearby

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import n7.kcalai.model.Log

/**
 * Поиск соседей через системный mDNS.
 *
 * `NsdManager` — та же DNS-SD, что Bonjour на iOS, поэтому андроидный телефон
 * и айфон видят друг друга без всяких мостов.
 *
 * Мультикаст на Android по умолчанию отфильтрован ради батареи, и без
 * `MulticastLock` объявления соседей просто не доходят. Замок берётся на время
 * поиска и отпускается вместе с ним.
 */
class NsdDiscovery(context: Context) : NearbyDiscovery {

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    private var registration: NsdManager.RegistrationListener? = null

    /** Имя, которым объявились сами: по нему своё объявление отсеивается из выдачи. */
    private var advertisedName: String? = null

    override suspend fun advertise(name: String, port: Int) {
        val manager = nsd ?: return
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = OFFER_SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "не удалось объявить себя соседям: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        advertisedName = name
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "объявление не вышло: $it") }
    }

    override fun peers(): Flow<List<Peer>> = callbackFlow {
        val manager = nsd ?: run {
            close()
            return@callbackFlow
        }
        // Без замка мультикаст до нас не доходит: система режет его ради батареи.
        val lock = wifi?.createMulticastLock(TAG)?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }.onFailure { Log.w(TAG, "мультикаст не включился: $it") }
        }

        val found = mutableMapOf<String, Peer>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // Своё же объявление в выдаче не нужно: предлагать еду себе незачем.
                if (info.serviceName == advertisedName) return
                manager.resolveService(info, resolveListener(found) { trySend(found.values.toList()) })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                trySend(found.values.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "поиск соседей не начался: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        runCatching { manager.discoverServices(OFFER_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.w(TAG, "поиск соседей не запустился: $it")
                close()
            }

        awaitClose {
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { lock?.release() }
        }
    }

    override fun stop() {
        stopAdvertising()
    }

    private fun stopAdvertising() {
        val manager = nsd ?: return
        registration?.let { runCatching { manager.unregisterService(it) } }
        registration = null
        advertisedName = null
    }

    @Suppress("DEPRECATION") // Вариант с Executor только с API 34, а minSdk здесь 26.
    private fun resolveListener(
        found: MutableMap<String, Peer>,
        onChanged: () -> Unit,
    ) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            // Сосед мог уйти, пока мы его разрешали — обычное дело.
            Log.i(TAG, "сосед не отозвался: $errorCode")
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            found[info.serviceName] = Peer(name = info.serviceName, host = host, port = info.port)
            onChanged()
        }
    }

    private companion object {
        const val TAG = "NsdDiscovery"
    }
}
