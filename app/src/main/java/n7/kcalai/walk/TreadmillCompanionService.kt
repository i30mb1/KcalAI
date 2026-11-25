package n7.kcalai.walk

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import n7.kcalai.KcalApp

/**
 * Система будит этот сервис, когда выбранная беговая дорожка включилась рядом, и держит приложение
 * живым, пока она рядом, — даже закрытое. Здесь только Bluetooth: подключиться и отдавать пакеты в
 * общий `TreadmillFeed`, где из них получаются прогулки. Ушла дорожка — отключаемся.
 */
class TreadmillCompanionService : CompanionDeviceService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gatt: TreadmillGatt? = null

    private val feed get() = (application as KcalApp).container.treadmillFeed

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> appeared()
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED, DevicePresenceEvent.EVENT_BT_DISCONNECTED -> disappeared()
        }
    }

    @Deprecated("Android 13–15; с Android 16 — onDevicePresenceEvent")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared()

    @Deprecated("Android 13–15; с Android 16 — onDevicePresenceEvent")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = disappeared()

    @Deprecated("Android 12")
    override fun onDeviceAppeared(address: String) = appeared()

    @Deprecated("Android 12")
    override fun onDeviceDisappeared(address: String) = disappeared()

    private fun appeared() {
        if (gatt != null) return
        val address = TreadmillCompanion(this).address ?: return
        gatt = TreadmillGatt(
            context = this,
            address = address,
            onPacket = { bytes -> scope.launch { feed.packet(bytes) } },
            onGone = feed::gone,
        ).takeIf { it.connect(autoConnect = false) }
    }

    private fun disappeared() {
        gatt?.close()
        gatt = null
        feed.gone()
    }

    override fun onDestroy() {
        disappeared()
        scope.cancel()
        super.onDestroy()
    }
}
