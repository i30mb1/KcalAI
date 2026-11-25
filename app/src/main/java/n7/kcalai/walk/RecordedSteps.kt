package n7.kcalai.walk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.LocalRecordingClient
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import java.util.concurrent.TimeUnit
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import n7.kcalai.repositories.PhoneSteps

/**
 * Своя запись шагов телефона — Recording API сервисов Google Play: датчик шагов пишется в фоне, без аккаунта,
 * и данные остаются на телефоне. Нужна там, где в Health Connect шаги никто не пишет — а таких телефонов много.
 * Считает с момента подписки: прошлых дней до неё нет. Только чтение за промежуток; что с ним делать, решает
 * общий код. Нет сервисов Google или разрешения «Физическая активность» — `null`.
 */
class RecordedSteps(context: Context) : PhoneSteps {

    private val app = context.applicationContext

    /** Есть ли на телефоне сервисы Google нужной версии — иначе и спрашивать нечего. */
    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(app, LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE) == ConnectionResult.SUCCESS

    /** Дал ли человек читать физическую активность — без этого запись шагов не включается. */
    val permitted: Boolean
        get() = ContextCompat.checkSelfPermission(app, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private val client by lazy { FitnessLocal.getLocalRecordingClient(app) }

    /** Подписка на шаги — одна на процесс; повторная ничего не меняет, поэтому её не помнят дольше процесса. */
    @Volatile
    private var subscribed = false

    /** Включить запись, если можно: после разрешения и на старте процесса. */
    suspend fun start(): Boolean {
        if (!available || !permitted) return false
        if (!subscribed) subscribed = runCatching { client.subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA).await() }.isSuccess
        return subscribed
    }

    override suspend fun between(from: Long, to: Long): Int? {
        if (to <= from || !start()) return null
        return runCatching {
            val request = LocalDataReadRequest.Builder()
                .read(LocalDataType.TYPE_STEP_COUNT_DELTA)
                .setTimeRange(from, to, TimeUnit.MILLISECONDS)
                .build()
            client.readData(request).await()
                .getDataSet(LocalDataType.TYPE_STEP_COUNT_DELTA)
                .dataPoints
                .sumOf { it.getValue(LocalField.FIELD_STEPS).asInt() }
        }.getOrNull()
    }

    companion object {
        /** «Физическая активность»: с Android 10 — разрешение, которое спрашивают у человека. */
        const val PERMISSION = Manifest.permission.ACTIVITY_RECOGNITION
    }
}

/** Ответ сервисов Google — без ещё одной библиотеки ради одного ожидания. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
