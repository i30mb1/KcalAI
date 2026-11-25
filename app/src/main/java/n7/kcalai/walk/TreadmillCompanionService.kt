package n7.kcalai.walk

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import n7.kcalai.KcalApp

/**
 * Система будит этот сервис, когда выбранная беговая дорожка включилась рядом, — даже закрытое приложение.
 * Сам он только сигнал: связь держит [TreadmillLink] процесса, потому что сервис система отвязывает
 * через несколько секунд, а связь должна жить, пока по дорожке идут.
 */
class TreadmillCompanionService : CompanionDeviceService() {

    private val link get() = (application as KcalApp).container.treadmillLink

    /** Система привязывает сервис, только когда дорожка рядом, — а об уже замеченной второй раз не сообщает. */
    override fun onCreate() {
        super.onCreate()
        link.appeared()
    }

    /*
     * Подключённая дорожка не показывается в эфире, и система через десяток секунд зовёт её «потерянной»
     * по эфиру — хотя связь жива. Поэтому «пропала из эфира» при живой связи ничего не значит: связь
     * забывается, только когда она и правда оборвалась.
     */
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED -> link.appeared()
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> if (!link.connected) link.lost()
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> link.lost()
        }
    }

    @Deprecated("Android 13–15; с Android 16 — onDevicePresenceEvent")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = link.appeared()

    @Deprecated("Android 13–15; с Android 16 — onDevicePresenceEvent")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (!link.connected) link.lost()
    }

    @Deprecated("Android 12")
    override fun onDeviceAppeared(address: String) = link.appeared()

    @Deprecated("Android 12")
    override fun onDeviceDisappeared(address: String) {
        if (!link.connected) link.lost()
    }
}
