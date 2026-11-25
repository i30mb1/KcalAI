package n7.kcalai.repositories

import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import n7.kcalai.model.TreadmillSample
import n7.kcalai.model.parseTreadmillData

/**
 * Беговая дорожка, подключённая к телефону. Платформа держит Bluetooth (Android — спутник
 * CompanionDeviceManager, iOS — CoreBluetooth) и отдаёт сюда сырые пакеты FTMS; что с ними
 * делать, решает общий код: прогулка — в базу, живые числа — странице дневника.
 */
class TreadmillFeed(private val walks: WalkRepository, private val clock: Clock = Clock.System) {

    private val sample = MutableStateFlow<TreadmillSample?>(null)

    /** Последний пакет подключённой дорожки; `null` — дорожки нет на связи. */
    val live: StateFlow<TreadmillSample?> = sample.asStateFlow()

    /** Пакет «данные дорожки» (0x2ACD) как пришёл; битый — мимо. */
    suspend fun packet(bytes: ByteArray) {
        val parsed = parseTreadmillData(bytes) ?: return
        sample.value = parsed
        walks.record(parsed, clock.now().toEpochMilliseconds())
    }

    /** Дорожка отключилась или ушла из виду. */
    fun gone() {
        sample.value = null
    }
}
