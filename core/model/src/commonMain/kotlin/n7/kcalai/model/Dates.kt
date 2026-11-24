package n7.kcalai.model

import kotlinx.datetime.LocalDate

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
