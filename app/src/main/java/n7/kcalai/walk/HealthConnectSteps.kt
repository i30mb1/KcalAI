package n7.kcalai.walk

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import n7.kcalai.repositories.PhoneSteps

/**
 * Шагомер телефона на Android — Health Connect: там собраны шаги самого телефона и приложений вроде
 * Samsung Health и Google Fit. Только чтение за промежуток; что с ним делать, решает общий код.
 * Без Health Connect или без разрешения — `null`: шагов телефона нет, считается только дорожка.
 */
class HealthConnectSteps(context: Context) : PhoneSteps {

    private val app = context.applicationContext

    /** Есть ли на телефоне Health Connect — иначе и спрашивать нечего. */
    val available: Boolean get() = HealthConnectClient.getSdkStatus(app) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(app) }

    override suspend fun between(from: Long, to: Long): Int? {
        if (!available || to <= from) return null
        return runCatching {
            if (READ !in client.permissionController.getGrantedPermissions()) return null
            val total = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)),
                ),
            )[StepsRecord.COUNT_TOTAL]
            total?.toInt() ?: 0
        }.getOrNull()
    }

    companion object {
        val READ = HealthPermission.getReadPermission(StepsRecord::class)

        /**
         * Только шаги, без чтения в фоне: с погасшим экраном Health Connect молчит, и страница дневника
         * показывает последние известные шаги дня (StepsRepository) — они обновятся, когда экран включат.
         */
        val PERMISSIONS = setOf(READ)
    }
}
