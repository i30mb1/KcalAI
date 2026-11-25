package n7.kcalai.walk

import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.edit
import androidx.core.content.IntentCompat

/**
 * Беговая дорожка — спутник телефона (CompanionDeviceManager). Её выбирают один раз системным окном,
 * и дальше система сама будит приложение, когда дорожка включилась рядом (см. [TreadmillCompanionService]),
 * даже если приложение закрыто. Без своего сервиса переднего плана и без поиска в фоне: держит
 * подключение система, пока дорожка рядом.
 */
class TreadmillCompanion(context: Context) {

    private val app = context.applicationContext
    private val manager = app.getSystemService(CompanionDeviceManager::class.java)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Адрес выбранной дорожки; `null` — ещё не выбирали. */
    val address: String? get() = prefs.getString(KEY_ADDRESS, null)

    /** Телефон умеет спутников — без них остаётся подключение, пока жив процесс. */
    val supported: Boolean get() = manager != null

    /**
     * Показать системный выбор дорожки: в списке только тренажёры FTMS рядом.
     *
     * @param onChooser окно выбора — запустить через `StartIntentSenderForResult`
     * @param onChosen выбор сделан (Android 13+ сообщает о нём сам; раньше — результатом окна, см. [chosen])
     */
    fun choose(onChooser: (IntentSender) -> Unit, onChosen: (Boolean) -> Unit) {
        val cdm = manager ?: return onChosen(false)
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(TreadmillGatt.FTMS)).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)

            @Deprecated("До Android 13 окно выбора приходит сюда")
            override fun onDeviceFound(intentSender: IntentSender) = onChooser(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                val mac = associationInfo.deviceMacAddress?.toString()?.uppercase()
                if (mac != null) remember(mac, associationInfo.id)
                onChosen(mac != null)
            }

            override fun onFailure(error: CharSequence?) = onChosen(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.associate(request, app.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request, callback, null)
        }
    }

    /**
     * Результат окна выбора. С Android 13 выбор приходит в [choose] сам — здесь только отказ;
     * раньше дорожка — в результате окна.
     *
     * @return выбрана ли дорожка; `null` — ждать сообщения о выборе в [choose]
     */
    fun chosen(ok: Boolean, data: Intent?): Boolean? {
        if (!ok) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return null
        val result = data?.let { IntentCompat.getParcelableExtra(it, CompanionDeviceManager.EXTRA_DEVICE, ScanResult::class.java) }
        val mac = result?.device?.address ?: return false
        remember(mac, NO_ID)
        return true
    }

    /** Просить систему будить приложение, когда выбранная дорожка рядом. */
    fun observe() {
        val cdm = manager ?: return
        val mac = address ?: return
        runCatching {
            val id = prefs.getInt(KEY_ID, NO_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && id != NO_ID) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(mac)
            }
        }
    }

    /** Будит ли система приложение сама; ниже Android 12 — нет, подключение держит процесс. */
    val wakes: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun remember(mac: String, id: Int) = prefs.edit {
        putString(KEY_ADDRESS, mac)
        putInt(KEY_ID, id)
    }

    private companion object {
        const val PREFS = "treadmill"
        const val KEY_ADDRESS = "address"
        const val KEY_ID = "association"
        const val NO_ID = -1
    }
}
