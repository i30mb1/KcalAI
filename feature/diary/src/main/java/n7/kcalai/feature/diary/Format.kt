package n7.kcalai.feature.diary

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import n7.kcalai.model.MealType
import n7.kcalai.personal.DayOutline

private val RU = Locale("ru")

/** Сотые грамма -> «4,6». Целые значения показываются без хвоста. */
fun formatCentigrams(cg: Int): String {
    val whole = cg / 100
    val tenths = (cg % 100) / 10
    return if (tenths == 0) whole.toString() else "$whole,$tenths"
}

/** «200 г» / «1,2 кг» — вес записи. */
fun formatGrams(grams: Int): String =
    if (grams >= 1000) "${formatCentigrams(grams / 10)} кг" else "$grams г"

/** «пн», «вт» — ось графика за неделю: полное название дня туда семь раз не помещается. */
fun formatWeekdayShort(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).trimEnd('.')

/** Название приёма пищи в именительном падеже. */
fun mealName(meal: MealType): String = when (meal) {
    MealType.BREAKFAST -> "Завтрак"
    MealType.LUNCH -> "Обед"
    MealType.DINNER -> "Ужин"
    MealType.SNACK -> "Перекус"
}

/** «13:00» — час без минут: точнее модель привычек всё равно не знает. */
fun formatHour(hour: Int): String = "%02d:00".format(hour)

/** «понедельник, 14 сент» — шапка. Месяц сокращён: строка стоит рядом с заголовком. */
fun formatDateCaps(date: LocalDate): String {
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, RU)
    val month = date.month.getDisplayName(TextStyle.SHORT, RU).trimEnd('.')
    return "$weekday, ${date.dayOfMonth} $month"
}

/** «14 сент» — дата начала действия цели в шите. */
fun formatDayMonthShort(date: LocalDate): String =
    "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, RU).trimEnd('.')}"

/** «14:10» — время записи в местной зоне: `createdAt` хранится в UTC-миллисекундах. */
fun formatTime(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().let {
        "%02d:%02d".format(it.hour, it.minute)
    }

/** «82,4 кг» — вес тела. Десятые обязательны: без них взвешивание бессмысленно. */
fun formatKg(weightGrams: Int): String {
    val whole = weightGrams / 1000
    val tenths = (weightGrams % 1000) / 100
    return "$whole,$tenths кг"
}

/**
 * Приветствие по часу.
 *
 * Единственное место, где приложение обращается к человеку первым, — поэтому
 * и единственное, где уместна вежливость. Дальше оно говорит только по делу.
 */
fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Доброе утро!"
    in 12..17 -> "Добрый день!"
    else -> "Добрый вечер!"
}

/** «на ужин» — какой приём впереди. По часу, а не по тому, что уже записано. */
fun nextMealTitle(hour: Int): String = when {
    hour < 11 -> "на завтрак"
    hour < 16 -> "на обед"
    else -> "на ужин"
}

/** «обед» — название приёма в середине фразы. */
fun mealNameLower(meal: MealType): String = mealName(meal).lowercase(RU)

/** «обеда» — «сегодня обеда в дневнике нет». */
fun mealNameGenitive(meal: MealType): String = when (meal) {
    MealType.BREAKFAST -> "завтрака"
    MealType.LUNCH -> "обеда"
    MealType.DINNER -> "ужина"
    MealType.SNACK -> "перекуса"
}

/** «обедаете» — «обычно вы обедаете около 13:00». */
fun mealVerb(meal: MealType): String = when (meal) {
    MealType.BREAKFAST -> "завтракаете"
    MealType.LUNCH -> "обедаете"
    MealType.DINNER -> "ужинаете"
    MealType.SNACK -> "перекусываете"
}

/**
 * «Обычно завтрак у вас около 08:00 и это ~450 ккал, обед ~850, ужин ~600».
 *
 * Время названо один раз, у первого приёма. Повторять «обед около 13:00 и это
 * ~850 ккал, ужин около 19:00 и это ~600 ккал» значит превратить фразу в таблицу,
 * которую никто не дочитает.
 */
fun outlineSentence(outline: DayOutline): String {
    val first = outline.meals.firstOrNull() ?: return ""
    val head = "Обычно ${mealNameLower(first.meal)} у вас около ${formatHour(first.hour)} " +
        "и это ~${first.kcal} ккал"
    val rest = outline.meals.drop(1).joinToString("") { ", ${mealNameLower(it.meal)} ~${it.kcal}" }
    return head + rest
}

/**
 * «день» / «дня» / «дней» — иначе «уложились в цель 1 дней».
 *
 * Правило русского счётного слова целиком: 11–14 берут форму множественного,
 * и без этой оговорки «11 дня» появляется ровно тогда, когда его не ждут.
 */
fun daysWord(count: Int): String {
    val tens = count % 100
    if (tens in 11..14) return "дней"
    return when (count % 10) {
        1 -> "день"
        2, 3, 4 -> "дня"
        else -> "дней"
    }
}
