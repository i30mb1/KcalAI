package n7.kcalai.repositories

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import n7.kcalai.model.TreadmillSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/** Шаги дня — телефона и дорожки вместе, без двойного счёта: телефон в кармане на дорожке тоже шагает. */
class StepsRepositoryTest {

    private val zone = TimeZone.UTC
    private val walkDao = MemoryWalkDao()
    private val walks = WalkRepository(walkDao, zone)
    private val today = LocalDate(2026, 11, 25).toEpochDays()
    private fun at(hour: Int, minute: Int = 0) = LocalDateTime(2026, 11, 25, hour, minute).toInstant(zone).toEpochMilliseconds()

    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(at(20))
    }

    /** Телефон: 100 шагов в час весь день, а пока шли по дорожке (18:00–18:30) — ещё 1500 «шагов в кармане». */
    private val pocket = object : PhoneSteps {
        override suspend fun between(from: Long, to: Long): Int {
            val hours = (to - from) / 3_600_000.0
            val walk = (minOf(to, at(18, 30)) - maxOf(from, at(18))).coerceAtLeast(0) / 1_800_000.0
            return (hours * 100 + walk * 1_500).toInt()
        }
    }

    /**
     * Шагомеров у телефона бывает два: Health Connect (туда пишут другие приложения) и своя запись шагов.
     * Пустой Health Connect отвечает нулём — верим тому, кто насчитал больше; молчат оба — шагов нет.
     */
    @Test
    fun `из двух шагомеров — тот, что насчитал больше`() = runBlocking {
        val empty = PhoneSteps { _, _ -> 0 }
        val recorded = PhoneSteps { _, _ -> 4_200 }
        val silent = PhoneSteps { _, _ -> null }

        assertEquals(4_200, BestPhoneSteps(listOf(empty, recorded)).between(0, 1))
        assertEquals(0, BestPhoneSteps(listOf(empty, silent)).between(0, 1))
        assertNull(BestPhoneSteps(listOf(silent, silent)).between(0, 1))
    }

    /** Health Connect, куда никто не пишет, отвечает нулём. Это не «не ходил», а «шагов нет»: плашке с нулём неоткуда взяться. */
    @Test
    fun `копилка пуста всю неделю — шагов нет, а не ноль`() = runBlocking {
        assertNull(WrittenSteps(PhoneSteps { _, _ -> 0 }).between(at(0), at(20)))
        assertNull(WrittenSteps(PhoneSteps { _, _ -> null }).between(at(0), at(20)))
    }

    @Test
    fun `в копилку пишут — утренний ноль остаётся нулём`() = runBlocking {
        val onlyBeforeToday = PhoneSteps { from, _ -> if (from < at(0)) 3_000 else 0 }

        assertEquals(0, WrittenSteps(onlyBeforeToday).between(at(0), at(8)))
        assertEquals(3_000, WrittenSteps(onlyBeforeToday).between(at(0) - 3_600_000, at(8)))
    }

    private suspend fun walk() = walks.record(TreadmillSample(speedKmh = 4.0, distanceM = 2_000, kcal = 80, elapsedS = 1_800), at(18, 30))

    @Test
    fun `шаги телефона и дорожки складываются, а телефон на дорожке не считается дважды`() = runBlocking {
        walk()
        val day = StepsRepository(walks, pocket, zone, clock).day(today)

        val treadmill = walks.day(today).single().steps.toInt()
        assertEquals(treadmill, day.treadmill)
        assertEquals(1_950, day.phone, "20 часов по 100, без получаса прогулки — без её 50 и 1500 в кармане")
        assertEquals(day.phone!! + treadmill, day.total)
    }

    @Test
    fun `телефон шаги не отдаёт — только дорожка`() = runBlocking {
        walk()
        val silent = object : PhoneSteps {
            override suspend fun between(from: Long, to: Long): Int? = null
        }
        val day = StepsRepository(walks, silent, zone, clock).day(today)

        assertNull(day.phone)
        assertEquals(day.treadmill, day.total)
    }

    /**
     * Health Connect отвечает приложению только на переднем плане: погас экран — шаги телефона на время пропадают.
     * Шаги дня не убывают, поэтому остаются последние известные, и счётчик на странице не прыгает вниз.
     */
    @Test
    fun `телефон замолчал — остаются последние известные шаги дня`() = runBlocking {
        var awake = true
        val sleepy = object : PhoneSteps {
            override suspend fun between(from: Long, to: Long): Int? = if (awake) pocket.between(from, to) else null
        }
        val steps = StepsRepository(walks, sleepy, zone, clock)
        val known = steps.day(today).phone

        awake = false
        assertEquals(known, steps.day(today).phone)
    }

    @Test
    fun `неделя шагов — по дням, вместе с телефоном`() = runBlocking {
        walk()
        val week = StepsRepository(walks, pocket, zone, clock).observeWeek(today).first()

        assertEquals((today - 6..today).toList(), week.map { it.first })
        assertEquals(StepsRepository(walks, pocket, zone, clock).day(today).total, week.last().second)
        assertEquals(2_400, week.first().second, "прошлый день — целиком: 24 часа по 100")
    }
}
