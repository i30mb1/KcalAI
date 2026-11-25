package n7.kcalai.ios

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import n7.kcalai.repositories.PhoneSteps
import platform.CoreMotion.CMAuthorizationStatusAuthorized
import platform.CoreMotion.CMPedometer
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * Шагомер iPhone — Core Motion: шаги, которые телефон насчитал сам (история — до недели). Только чтение
 * за промежуток; что с ним делать, решает общий код. Без разрешения «Движение и фитнес» — `null`.
 */
class IosPhoneSteps : PhoneSteps {

    private val pedometer = CMPedometer()

    /** Умеет ли телефон считать шаги — иначе и спрашивать нечего. */
    val available: Boolean get() = CMPedometer.isStepCountingAvailable()

    override suspend fun between(from: Long, to: Long): Int? {
        if (!available || to <= from || CMPedometer.authorizationStatus() != CMAuthorizationStatusAuthorized) return null
        return query(from, to)
    }

    /** Первый запрос шагов показывает системный вопрос «Движение и фитнес». @return разрешили ли */
    suspend fun request(): Boolean {
        if (!available) return false
        val now = NSDate().timeIntervalSince1970.toLong() * MS_PER_S
        query(now - HOUR_MS, now)
        return CMPedometer.authorizationStatus() == CMAuthorizationStatusAuthorized
    }

    private suspend fun query(from: Long, to: Long): Int? = suspendCancellableCoroutine { done ->
        pedometer.queryPedometerDataFromDate(date(from), toDate = date(to)) { data, error ->
            if (done.isActive) done.resume(if (error != null) null else data?.numberOfSteps?.intValue)
        }
    }

    private fun date(ms: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(ms / MS_PER_S.toDouble())

    private companion object {
        const val MS_PER_S = 1_000L
        const val HOUR_MS = 3_600_000L
    }
}
