package n7.kcalai.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import n7.kcalai.model.ShakeDetector
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue
import kotlin.math.roundToLong

/**
 * Тряска телефона — потоком, пока его собирают.
 *
 * Акселерометр слушается только пока есть подписчик: экран снимает подписку,
 * уходя с глаз, и в кармане датчик не работает. Без акселерометра поток пуст.
 *
 * Отличить тряску от обычной жизни телефона умеет общий [ShakeDetector] — тот же,
 * что на Android, и проверенный общими тестами. Здесь только показания датчика.
 */
@OptIn(ExperimentalForeignApi::class)
fun phoneShakes(): Flow<Unit> = callbackFlow {
    val manager = CMMotionManager()
    if (!manager.accelerometerAvailable) {
        close()
        return@callbackFlow
    }

    val detector = ShakeDetector()
    manager.accelerometerUpdateInterval = UPDATE_INTERVAL_SECONDS
    manager.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
        val sample = data?.acceleration ?: return@startAccelerometerUpdatesToQueue
        // CoreMotion меряет в g, порог детектора — в м/с², как на Android.
        // Знак тоже обратный: у Apple лежащий экраном вверх телефон даёт -1 по z,
        // но детектор считает длину вектора, и на неё это не влияет.
        val shaken = sample.useContents {
            detector.onSample(
                x = (x * GRAVITY).toFloat(),
                y = (y * GRAVITY).toFloat(),
                z = (z * GRAVITY).toFloat(),
                atMs = (data.timestamp * MILLIS_PER_SECOND).roundToLong(),
            )
        }
        if (shaken) trySend(Unit)
    }
    awaitClose { manager.stopAccelerometerUpdates() }
}

/** То же, что `SENSOR_DELAY_UI` на Android: чаще для тряски незачем. */
private const val UPDATE_INTERVAL_SECONDS = 1.0 / 60
private const val MILLIS_PER_SECOND = 1_000.0
private const val GRAVITY = 9.81
