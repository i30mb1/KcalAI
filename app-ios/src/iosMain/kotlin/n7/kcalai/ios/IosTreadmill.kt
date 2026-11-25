package n7.kcalai.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import n7.kcalai.repositories.TreadmillFeed
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * Беговая дорожка у iPhone — CoreBluetooth. Только Bluetooth: пакеты «данных дорожки» FTMS уходят
 * в общий [TreadmillFeed], где из них получаются прогулки.
 *
 * Системного окна выбора на iOS нет: на «Подключить» берётся первая дорожка FTMS рядом, и её имя
 * запоминается. Дальше — отложенное подключение: iOS сама подключится, когда дорожка включится,
 * и разбудит приложение в фоне (UIBackgroundModes `bluetooth-central` и восстановление состояния).
 * Bluetooth спрашивается, только когда дорожку выбирают или она уже выбрана: остальным — ни слова.
 */
@OptIn(ExperimentalForeignApi::class)
class IosTreadmill(private val feed: TreadmillFeed, private val scope: CoroutineScope) {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val delegate = Delegate()
    private var central: CBCentralManager? = null
    private var peripheral: CBPeripheral? = null
    private var choosing: CompletableDeferred<Boolean>? = null

    /** Что сделать, когда Bluetooth включится: искать новую дорожку или ждать знакомую. */
    private var pending: (() -> Unit)? = null

    /** Найти дорожку рядом и запомнить её. @return нашлась ли за [CHOOSE_MS] */
    suspend fun choose(): Boolean {
        val found = CompletableDeferred<Boolean>()
        choosing = found
        whenOn { central?.scanForPeripheralsWithServices(listOf(CBUUID.UUIDWithString(FTMS)), null) }
        val chosen = withTimeoutOrNull(CHOOSE_MS) { found.await() } ?: false
        central?.stopScan()
        choosing = null
        return chosen
    }

    /** Старт приложения: знакомую дорожку ждать отложенным подключением. */
    fun resume() {
        if (defaults.stringForKey(KEY_ID) == null) return
        whenOn(::reconnect)
    }

    private fun whenOn(action: () -> Unit) {
        val manager = central ?: CBCentralManager(delegate, null, mapOf(CBCentralManagerOptionRestoreIdentifierKey to RESTORE_ID)).also { central = it }
        if (manager.state == CBManagerStatePoweredOn) action() else pending = action
    }

    private fun reconnect() {
        val manager = central ?: return
        val id = defaults.stringForKey(KEY_ID)?.let { NSUUID(uUIDString = it) } ?: return
        val known = manager.retrievePeripheralsWithIdentifiers(listOf(id)).firstOrNull() as? CBPeripheral ?: return
        attach(known)
        // Отложенное подключение не истекает: iOS подключится, когда дорожка появится рядом.
        manager.connectPeripheral(known, null)
    }

    private fun attach(found: CBPeripheral) {
        peripheral = found
        found.delegate = delegate
    }

    private inner class Delegate : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state != CBManagerStatePoweredOn) return
            pending?.invoke()
            pending = null
        }

        override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
            // Разбудили в фоне: дорожка, которую ждали, уже на подходе — продолжаем с ней.
            whenOn(::reconnect)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            central.stopScan()
            defaults.setObject(didDiscoverPeripheral.identifier.UUIDString, KEY_ID)
            attach(didDiscoverPeripheral)
            central.connectPeripheral(didDiscoverPeripheral, null)
            choosing?.complete(true)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            didConnectPeripheral.discoverServices(listOf(CBUUID.UUIDWithString(FTMS)))
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            feed.gone()
            // Дорожку выключили — ждём, когда включат снова.
            central.connectPeripheral(didDisconnectPeripheral, null)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            central.connectPeripheral(didFailToConnectPeripheral, null)
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val service = peripheral.services?.firstOrNull { (it as CBService).UUID == CBUUID.UUIDWithString(FTMS) } as? CBService ?: return
            peripheral.discoverCharacteristics(listOf(CBUUID.UUIDWithString(DATA)), service)
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            val data = didDiscoverCharacteristicsForService.characteristics
                ?.firstOrNull { (it as CBCharacteristic).UUID == CBUUID.UUIDWithString(DATA) } as? CBCharacteristic ?: return
            peripheral.setNotifyValue(true, data)
        }

        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            val bytes = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
            scope.launch { feed.packet(bytes) }
        }
    }

    private companion object {
        const val FTMS = "1826"
        const val DATA = "2ACD"
        const val KEY_ID = "treadmill.peripheral"
        const val RESTORE_ID = "n7.kcalai.treadmill"
        const val CHOOSE_MS = 30_000L
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
