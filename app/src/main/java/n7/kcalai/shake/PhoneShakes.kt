package n7.kcalai.shake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Тряска телефона — потоком, пока его собирают.
 *
 * Акселерометр слушается только пока есть подписчик: экран снимает подписку,
 * уходя с глаз, и в кармане датчик не работает. Без акселерометра поток пуст.
 */
fun phoneShakes(context: Context): Flow<Unit> = callbackFlow {
    val manager = context.getSystemService(SensorManager::class.java)
    val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    if (manager == null || sensor == null) {
        close()
        return@callbackFlow
    }

    val detector = ShakeDetector()
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = event.values
            if (detector.onSample(values[0], values[1], values[2], event.timestamp / NANOS_PER_MS)) trySend(Unit)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    awaitClose { manager.unregisterListener(listener) }
}

private const val NANOS_PER_MS = 1_000_000L
