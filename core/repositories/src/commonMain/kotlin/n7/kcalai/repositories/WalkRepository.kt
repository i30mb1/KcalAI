package n7.kcalai.repositories

import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import n7.kcalai.database.WalkDao
import n7.kcalai.database.WalkEntity
import n7.kcalai.model.DEFAULT_HEIGHT_CM
import n7.kcalai.model.TreadmillSample
import n7.kcalai.model.Walk
import n7.kcalai.model.track

/**
 * Прогулки по беговой дорожке. Пакеты шлёт страница дневника, к которой дорожка подключена
 * по Bluetooth; здесь они складываются в прогулки (см. [track]) и живут в базе телефона.
 *
 * @param height рост человека, см: по нему меряется шаг — дорожка знает путь, а не шаги
 */
class WalkRepository(
    private val dao: WalkDao,
    private val zone: TimeZone,
    private val height: suspend () -> Int = { DEFAULT_HEIGHT_CM },
) {

    /** Пакет дорожки: продолжает последнюю прогулку или заводит новую. Рассказанная остаётся рассказанной. */
    suspend fun record(sample: TreadmillSample, now: Long) {
        val last = dao.last()
        val walk = track(last?.toWalk(), sample, now, height()) ?: return
        val told = last?.toldAt?.takeIf { last.startedAt == walk.startedAt }
        dao.upsert(walk.toEntity(dayOf(walk.startedAt), told))
    }

    fun observeDay(date: Long): Flow<List<Walk>> = dao.observeDays(date, date).map { list -> list.map { it.toWalk() } }

    /** Шаги семи дней, кончая [date]: пары (день, шаги), пустой день — ноль. */
    fun observeWeekSteps(date: Long): Flow<List<Pair<Long, Int>>> =
        dao.observeDays(date - WEEK_DAYS + 1, date).map { list ->
            (date - WEEK_DAYS + 1..date).map { day -> day to list.filter { it.dateEpochDay == day }.sumOf { it.steps }.roundToInt() }
        }

    /** Последняя прогулка и рассказано ли о ней. */
    fun observeLast(): Flow<WalkEntity?> = dao.observeLast()

    suspend fun day(date: Long): List<Walk> = dao.day(date).map { it.toWalk() }

    suspend fun markTold(walk: WalkEntity, at: Long) = dao.markTold(walk.startedAt, at)

    fun dayOf(ms: Long): Long = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date.toEpochDays()

    private companion object {
        const val WEEK_DAYS = 7
    }
}

fun WalkEntity.toWalk() = Walk(startedAt, seenAt, distanceM, kcal, elapsedS, steps, topSpeedKmh)

private fun Walk.toEntity(day: Long, toldAt: Long?) =
    WalkEntity(startedAt, day, seenAt, distanceM, kcal, elapsedS, steps, topSpeedKmh, toldAt)
