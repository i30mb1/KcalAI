package n7.kcalai.feature.diary

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import n7.kcalai.model.MealType

private val RU = Locale("ru")

/** Сотые грамма -> «4,6». Целые значения показываются без хвоста. */
fun formatCentigrams(cg: Int): String {
    val whole = cg / 100
    val tenths = (cg % 100) / 10
    return if (tenths == 0) whole.toString() else "$whole,$tenths"
}

/** «13 сентября, воскресенье» */
fun formatDate(date: LocalDate): String {
    val month = date.month.getDisplayName(TextStyle.FULL, RU)
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, RU)
    return "${date.dayOfMonth} $month, $weekday"
}

/** «200 г» / «1,2 кг» — вес записи. */
fun formatGrams(grams: Int): String =
    if (grams >= 1000) "${formatCentigrams(grams / 10)} кг" else "$grams г"

/** «пн», «вт» — ось графика за неделю: полное название дня туда семь раз не помещается. */
fun formatWeekdayShort(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).trimEnd('.')

/** «13 сентября» — без дня недели: на графике он уже подписан буквами. */
fun formatDayMonth(date: LocalDate): String =
    "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, RU)}"

/** Название приёма пищи в именительном падеже. */
fun mealName(meal: MealType): String = when (meal) {
    MealType.BREAKFAST -> "Завтрак"
    MealType.LUNCH -> "Обед"
    MealType.DINNER -> "Ужин"
    MealType.SNACK -> "Перекус"
}

/** «13:00» — час без минут: точнее модель привычек всё равно не знает. */
fun formatHour(hour: Int): String = "%02d:00".format(hour)
