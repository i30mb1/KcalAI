package n7.kcalai.walk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import n7.kcalai.model.FTMS_SERVICE
import n7.kcalai.model.TREADMILL_DATA

/**
 * Подключение к беговой дорожке по Bluetooth: подписка на «данные дорожки» FTMS — и только.
 * Пакеты отдаются как есть: что в них, разбирает общий код (`TreadmillFeed`).
 */
class TreadmillGatt(
    private val context: Context,
    private val address: String,
    private val onPacket: (ByteArray) -> Unit,
    private val onGone: () -> Unit,
) {
    private var gatt: BluetoothGatt? = null

    /**
     * @param autoConnect ждать, пока дорожка появится: так Android держит подключение сам, пока жив
     *        процесс, — там, где система не будит приложение по дорожке (ниже Android 12)
     * @return начато ли подключение; нет — Bluetooth выключен или не разрешён
     */
    @SuppressLint("MissingPermission")
    fun connect(autoConnect: Boolean): Boolean {
        if (!allowed(context)) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        return gatt != null
    }

    @SuppressLint("MissingPermission")
    fun close() {
        if (allowed(context)) gatt?.close()
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> onGone()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val data = gatt.getService(FTMS)?.getCharacteristic(DATA) ?: return
            gatt.setCharacteristicNotification(data, true)
            val cccd = data.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == DATA) onPacket(value)
        }

        @Deprecated("До Android 13 значение приходит в самой характеристике")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == DATA) characteristic.value?.let(onPacket)
        }
    }

    companion object {
        val FTMS: UUID = bluetoothUuid(FTMS_SERVICE)
        private val DATA: UUID = bluetoothUuid(TREADMILL_DATA)
        private val CCCD: UUID = bluetoothUuid(0x2902)

        /** Короткий номер сервиса Bluetooth SIG — в полный UUID. */
        private fun bluetoothUuid(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

        /** С Android 12 к устройству подключаются по разрешению «Устройства поблизости». */
        fun allowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
