package n7.kcalai.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/*
 * Названия — свои таблицы, а не локаль платформы. Сокращения те же, что даёт
 * CLDR на JVM и Android: «сент», «нояб», месяц в родительном падеже — «9 мая».
 */
private val WEEKDAYS_SHORT = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
private val WEEKDAYS_FULL =
    listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
private val MONTHS_SHORT =
    listOf("янв", "февр", "мар", "апр", "мая", "июн", "июл", "авг", "сент", "окт", "нояб", "дек")

private val LocalDate.weekdayIndex: Int get() = dayOfWeek.ordinal
private val LocalDate.monthShort: String get() = MONTHS_SHORT[month.ordinal]

/** «16–22 нояб», а через границу месяца — «27 окт – 2 нояб»: какая это неделя, видно сразу. */
fun formatWeekRange(first: LocalDate, last: LocalDate): String =
    if (first.month == last.month) {
        "${first.day}–${last.day} ${last.monthShort}"
    } else {
        "${first.day} ${first.monthShort} – ${last.day} ${last.monthShort}"
    }

/** «пн», «вт» — ось графика за неделю: полное название дня туда семь раз не помещается. */
fun formatWeekdayShort(date: LocalDate): String =
    WEEKDAYS_SHORT[date.weekdayIndex]

/** «понедельник, 14 сент» — шапка. Месяц сокращён: строка стоит рядом с заголовком. */
fun formatDateCaps(date: LocalDate): String {
    return "${WEEKDAYS_FULL[date.weekdayIndex]}, ${date.day} ${date.monthShort}"
}

/**
 * Чья переписка в ленте: «сегодня», «вчера», дальше — «четверг, 29 окт».
 *
 * Словом, пока это возможно: «вчера» читается быстрее даты и не требует
 * сверяться с календарём.
 */
fun dayLabel(day: LocalDate, today: LocalDate): String = when (today.toEpochDays() - day.toEpochDays()) {
    0L -> "сегодня"
    1L -> "вчера"
    else -> formatDateCaps(day)
}

/** «Четверг, 29 окт» — заголовок пустого прошлого дня. */
fun formatDayFull(day: LocalDate): String = formatDateCaps(day).replaceFirstChar(Char::uppercase)

/** «14:10» — время записи в местной зоне: `createdAt` хранится в UTC-миллисекундах. */
fun formatTime(millis: Long, zone: TimeZone): String =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).let {
        "${it.hour.twoDigits()}:${it.minute.twoDigits()}"
    }

/** «13:00» — час без минут: точнее модель привычек всё равно не знает. */
fun formatHour(hour: Int): String = "${hour.twoDigits()}:00"

private fun Int.twoDigits(): String = toString().padStart(2, '0')
