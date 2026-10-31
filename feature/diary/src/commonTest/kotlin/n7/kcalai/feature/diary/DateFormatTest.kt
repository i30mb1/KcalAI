package n7.kcalai.feature.diary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Названия дней и месяцев — свои таблицы, а не локаль платформы: на разных
 * платформах сокращения расходятся, а шапка дневника должна читаться одинаково.
 */
class DateFormatTest {

    @Test
    fun `шапка — полный день недели и сокращённый месяц`() {
        assertEquals("суббота, 31 окт", formatDateCaps(LocalDate(2026, 10, 31)))
        assertEquals("понедельник, 14 сент", formatDateCaps(LocalDate(2026, 9, 14)))
        assertEquals("воскресенье, 1 февр", formatDateCaps(LocalDate(2026, 2, 1)))
    }

    @Test
    fun `месяц в родительном падеже после числа`() {
        assertEquals("9 мая", formatDayMonthShort(LocalDate(2026, 5, 9)))
        assertEquals("30 нояб", formatDayMonthShort(LocalDate(2026, 11, 30)))
        assertEquals("1 июн", formatDayMonthShort(LocalDate(2026, 6, 1)))
    }

    @Test
    fun `ось графика — двухбуквенные дни`() {
        val week = (14..20).map { formatWeekdayShort(LocalDate(2026, 9, it)) }

        assertEquals(listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс"), week)
    }

    @Test
    fun `время записи в местной зоне`() {
        // 2025-09-13 00:00 UTC + 7 ч 5 мин.
        val millis = 1_757_721_600_000L + (7 * 60 + 5) * 60_000L

        assertEquals("07:05", formatTime(millis, TimeZone.UTC))
        assertEquals("10:05", formatTime(millis, TimeZone.of("Europe/Moscow")))
    }

    @Test
    fun `час с ведущим нулём`() {
        assertEquals("08:00", formatHour(8))
        assertEquals("13:00", formatHour(13))
    }
}
