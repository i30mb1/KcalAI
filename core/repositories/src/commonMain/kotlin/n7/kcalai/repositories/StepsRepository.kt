package n7.kcalai.repositories

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import n7.kcalai.model.Walk

/**
 * Шаги, которые насчитал сам телефон: Health Connect на Android, Core Motion на iOS. Платформа — только
 * чтение за промежуток; что с этим делать, решает общий код.
 */
fun interface PhoneSteps {
    /** Шаги за промежуток [from, to), мс; `null` — телефон их не отдаёт (нет счётчика или доступа). */
    suspend fun between(from: Long, to: Long): Int?
}

/**
 * Шагомеров у телефона бывает несколько: Health Connect, куда пишут другие приложения, и своя запись шагов
 * телефона. Пустой Health Connect отвечает нулём, поэтому верим тому, кто насчитал больше; молчат все — `null`.
 */
class BestPhoneSteps(private val sources: List<PhoneSteps>) : PhoneSteps {
    override suspend fun between(from: Long, to: Long): Int? = sources.mapNotNull { it.between(from, to) }.maxOrNull()
}

/**
 * Шагомер-копилка вроде Health Connect: сам не считает, а хранит то, что пишут другие приложения. Не пишет
 * никто — он отвечает нулём, и это не «не ходил», а «шагов нет»: пустой за неделю молчит.
 */
class WrittenSteps(private val source: PhoneSteps) : PhoneSteps {
    override suspend fun between(from: Long, to: Long): Int? {
        val steps = source.between(from, to) ?: return null
        if (steps > 0) return steps
        val week = source.between(to - WEEK_MS, to) ?: return null
        return if (week > 0) 0 else null
    }

    private companion object {
        const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

/** Телефон без шагомера — или пока человек не разрешил его читать. */
object NoPhoneSteps : PhoneSteps {
    override suspend fun between(from: Long, to: Long): Int? = null
}

/**
 * Шаги дня: телефона — [phone] (`null` — он их не отдаёт), дорожки — [treadmill]; цель одна на сумму.
 */
data class DaySteps(val phone: Int?, val treadmill: Int) {
    val total: Int get() = (phone ?: 0) + treadmill
}

/**
 * Шаги дня вместе — телефона и дорожки. Телефон, лежащий в кармане на дорожке, шагает вместе с ней,
 * поэтому его шаги за время прогулок по дорожке не считаются: их уже посчитала дорожка, точнее.
 */
class StepsRepository(
    private val walks: WalkRepository,
    private val phone: PhoneSteps,
    private val zone: TimeZone,
    private val clock: Clock = Clock.System,
) {

    /**
     * Последние известные шаги телефона по дням. Health Connect отвечает только на переднем плане: погас экран —
     * телефон на время молчит, а шаги дня не убывают, поэтому остаются последние известные.
     */
    private val known = MutableStateFlow<Map<Long, Int>>(emptyMap())

    suspend fun day(date: Long): DaySteps = stepsOf(date, walks.day(date))

    /** Шаги дня — заново на каждую прогулку и раз в минуту: телефон считает и без дорожки. */
    fun observeDay(date: Long): Flow<DaySteps> =
        combine(walks.observeDay(date), ticks()) { walked, _ -> walked }.map { stepsOf(date, it) }

    /** Шаги семи дней, кончая [date]: пары (день, шаги вместе). */
    fun observeWeek(date: Long): Flow<List<Pair<Long, Int>>> =
        combine(walks.observeWeekSteps(date), ticks()) { _, _ -> Unit }
            .map { (date - WEEK_DAYS + 1..date).map { day -> day to day(day).total } }

    private suspend fun stepsOf(date: Long, walked: List<Walk>): DaySteps {
        val treadmill = walked.sumOf { it.steps }.roundToInt()
        val from = LocalDate.fromEpochDays(date).atStartOfDayIn(zone).toEpochMilliseconds()
        val to = min(LocalDate.fromEpochDays(date + 1).atStartOfDayIn(zone).toEpochMilliseconds(), clock.now().toEpochMilliseconds())
        if (to <= from) return DaySteps(phone = 0, treadmill = treadmill)
        val all = phone.between(from, to) ?: return DaySteps(phone = known.value[date], treadmill = treadmill)
        val pocket = walked.sumOf { phone.between(max(it.startedAt, from), min(it.seenAt, to)) ?: 0 }
        val steps = max(0, all - pocket)
        known.update { it + (date to steps) }
        return DaySteps(phone = steps, treadmill = treadmill)
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    private companion object {
        const val WEEK_DAYS = 7
        const val TICK_MS = 60_000L
    }
}
