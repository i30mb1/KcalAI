package n7.kcalai.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

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
    fun `ось графика — двухбуквенные дни`() {
        val week = (14..20).map { formatWeekdayShort(LocalDate(2026, 9, it)) }

        assertEquals(listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс"), week)
    }

    /** Неделя над столбиками — датами: листая прошлое, видно, какая это неделя. */
    @Test
    fun `неделя — диапазоном дат, месяц один раз`() {
        assertEquals("16–22 нояб", formatWeekRange(LocalDate(2026, 11, 16), LocalDate(2026, 11, 22)))
        assertEquals("27 окт – 2 нояб", formatWeekRange(LocalDate(2025, 10, 27), LocalDate(2025, 11, 2)))
    }

    @Test
    fun `день ленты называется словом пока это возможно`() {
        val today = kotlinx.datetime.LocalDate(2026, 10, 31)

        assertEquals("сегодня", dayLabel(today, today))
        assertEquals("вчера", dayLabel(kotlinx.datetime.LocalDate(2026, 10, 30), today))
        assertEquals("четверг, 29 окт", dayLabel(kotlinx.datetime.LocalDate(2026, 10, 29), today))
    }
}
