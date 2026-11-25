package n7.kcalai.web

import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import n7.kcalai.model.TreadmillSample
import n7.kcalai.model.parseTreadmillData
import n7.kcalai.webapi.ToPhone

/** Как страница связана с беговой дорожкой — имена те же, что у адаптера в index.html. */
enum class TreadmillLink {
    /** Браузер не умеет Bluetooth — дорожки на этой странице не будет. */
    UNSUPPORTED,

    /** Дорожку ещё не выбирали: выбрать можно только по касанию. */
    NEED_PAIR,

    /** Знакомая дорожка выключена — подключимся, когда включат. */
    WAITING,
    CONNECTING,
    CONNECTED,

    /** Без касания не подключиться: так устроен Bluetooth в браузере. */
    NEED_TAP,
}

/**
 * Беговая дорожка у компьютера. К ней по Bluetooth подключается страница (адаптер — в index.html),
 * а прогулки хранит телефон: каждый пакет уходит ему как есть, складывает их в прогулки общий код.
 * Здесь — только живые числа для окошка шагов и мира прогулки.
 *
 * Демо (`?walk` в адресе) — дорожка понарошку: числа идут на странице, телефону не уходит ничего.
 */
class PageTreadmill(private val send: (ToPhone) -> Unit, private val scope: CoroutineScope) {

    private val linkState = MutableStateFlow(TreadmillLink.UNSUPPORTED)
    val link: StateFlow<TreadmillLink> = linkState.asStateFlow()

    private val liveState = MutableStateFlow<TreadmillSample?>(null)

    /** Последний пакет; `null` — дорожка молчит. */
    val live: StateFlow<TreadmillSample?> = liveState.asStateFlow()

    private var silence: Job? = null

    fun start() {
        if ("walk" in window.location.search) demo() else treadmillStart(::packet, ::changed)
    }

    /** Выбрать дорожку — только по касанию человека. */
    fun pair() = treadmillPair()

    private fun packet(hex: String) {
        val sample = parseTreadmillData(hex.hexBytes()) ?: return
        liveState.value = sample
        send(ToPhone.Treadmill(sample))
        silence?.cancel()
        silence = scope.launch {
            delay(SILENCE_MS)
            liveState.value = null
        }
    }

    private fun changed(name: String) {
        linkState.value = TreadmillLink.entries.firstOrNull { it.name.replace("_", "").equals(name, ignoreCase = true) } ?: return
        if (linkState.value != TreadmillLink.CONNECTED) liveState.value = null
    }

    private fun demo() {
        linkState.value = TreadmillLink.CONNECTED
        scope.launch {
            var meters = 0.0
            var seconds = 0
            while (true) {
                liveState.value = TreadmillSample(DEMO_KMH, meters.toInt(), (meters / DEMO_M_PER_KCAL).toInt(), seconds)
                delay(TICK_MS)
                meters += DEMO_KMH / KMH_PER_MPS
                seconds += 1
            }
        }
    }

    private companion object {
        /** Столько без пакета — и дорожка считается замолчавшей: она шлёт их раз в секунду. */
        const val SILENCE_MS = 6_000L
        const val TICK_MS = 1_000L
        const val DEMO_KMH = 4.0
        const val DEMO_M_PER_KCAL = 20.0
        const val KMH_PER_MPS = 3.6
    }
}

@Suppress("MagicNumber")
private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private fun treadmillStart(onPacket: (String) -> Unit, onState: (String) -> Unit): Unit =
    js("{ if (window.KcalTreadmill) window.KcalTreadmill.start(onPacket, onState); else onState('unsupported'); }")

private fun treadmillPair(): Unit = js("{ if (window.KcalTreadmill) window.KcalTreadmill.pair(); }")
