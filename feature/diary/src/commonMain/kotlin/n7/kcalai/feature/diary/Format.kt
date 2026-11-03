package n7.kcalai.feature.diary

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import n7.kcalai.model.MealType
import n7.kcalai.model.title
import n7.kcalai.personal.DayOutline

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

private fun Int.twoDigits(): String = toString().padStart(2, '0')

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
    WEEKDAYS_SHORT[date.weekdayIndex]

/** «13:00» — час без минут: точнее модель привычек всё равно не знает. */
fun formatHour(hour: Int): String = "${hour.twoDigits()}:00"

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

/** «14 сент» — дата начала действия цели в шите. */
fun formatDayMonthShort(date: LocalDate): String =
    "${date.day} ${date.monthShort}"

/** «14:10» — время записи в местной зоне: `createdAt` хранится в UTC-миллисекундах. */
fun formatTime(millis: Long, zone: TimeZone): String =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).let {
        "${it.hour.twoDigits()}:${it.minute.twoDigits()}"
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

/** «на ужин» — «обычно на ужин»: подсказки для приёма прошлого дня. */
fun mealNameTarget(meal: MealType): String = "на ${mealNameLower(meal)}"

/** «обед» — название приёма в середине фразы. */
fun mealNameLower(meal: MealType): String = meal.title.lowercase()

/** «обедаешь» — «обычно ты обедаешь около 13:00». */
fun mealVerb(meal: MealType): String = when (meal) {
    MealType.BREAKFAST -> "завтракаешь"
    MealType.LUNCH -> "обедаешь"
    MealType.DINNER -> "ужинаешь"
    MealType.SNACK -> "перекусываешь"
}

/**
 * «Обычно завтрак у тебя около 08:00 и это ~450 ккал, обед ~850, ужин ~600».
 *
 * Время названо один раз, у первого приёма. Повторять «обед около 13:00 и это
 * ~850 ккал, ужин около 19:00 и это ~600 ккал» значит превратить фразу в таблицу,
 * которую никто не дочитает.
 */
fun outlineSentence(outline: DayOutline): String {
    val first = outline.meals.firstOrNull() ?: return ""
    val head = "Обычно ${mealNameLower(first.meal)} у тебя около ${formatHour(first.hour)} " +
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

/**
 * Ответ на запрос, по которому ничего не нашлось, — с тремя выходами.
 *
 * Род запроса не угадать: «„Борщ“ не нашлась» режет глаз ровно тем, кто
 * пишет по-русски. Поэтому глагол стоит при «ничего», а не при запросе.
 */
fun notFoundText(query: String, assist: Assist = Assist.NONE): String = when (assist) {
    Assist.FAILED ->
        "Нейросеть тоже не разобрала «$query». Попробуй назвать блюдо иначе, " +
            "отсканируй штрих-код или сфотографируй этикетку — добавим в базу."
    else ->
        "По запросу «$query» ничего не нашлось. Попробуй назвать блюдо иначе, " +
            "отсканируй штрих-код или сфотографируй этикетку — добавим в базу."
}

/** «2 сессии съёмки и 1 продукт» — что лежит в очереди отправки. */
fun describeOutbox(scans: Int, products: Int): String {
    val parts = buildList {
        if (scans > 0) add("$scans ${plural(scans, "сессия", "сессии", "сессий")} съёмки")
        if (products > 0) add("$products ${plural(products, "продукт", "продукта", "продуктов")}")
    }
    return parts.joinToString(" и ")
}

/** Русское счётное слово по тому же правилу, что [daysWord]. */
private fun plural(count: Int, one: String, few: String, many: String): String {
    val tens = count % 100
    if (tens in 11..14) return many
    return when (count % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}
