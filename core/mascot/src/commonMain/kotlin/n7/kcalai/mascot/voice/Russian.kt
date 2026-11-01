package n7.kcalai.mascot.voice

import kotlinx.datetime.DayOfWeek

/*
 * Числа и названия так, как их пишет человек в переписке.
 *
 * Приложение пишет «3 ч 20 мин без еды». Друг пишет «уже три часа» — округляя
 * вниз, словами и без минут. Персонажи — друзья, а не отчёт.
 */

/**
 * «три часа», «полтора часа», «пять с половиной часов» — вниз до получаса.
 *
 * Вниз, а не к ближайшему: «уже три часа» в 3:20 — правда, а «уже четыре» в 3:40
 * человек проверит по часам и поймает персонажа на вранье.
 */
fun hoursPhrase(minutes: Int): String {
    if (minutes < 60) return "меньше часа"
    val hours = minutes / 60
    val half = minutes % 60 >= 30
    return when {
        hours > MAX_HOURS -> "больше двенадцати часов"
        hours == 1 -> if (half) "полтора часа" else "час"
        // Существительное согласуется с целой частью: «два с половиной часа», «пять с половиной часов».
        half -> "${numberWord(hours)} с половиной ${plural(hours, "час", "часа", "часов")}"
        else -> "${numberWord(hours)} ${plural(hours, "час", "часа", "часов")}"
    }
}

private const val MAX_HOURS = 12

/**
 * Сколько человека не было: «два дня», «неделю», «больше недели».
 *
 * Винительный падеж длительности — «тебя не было неделю». Дальше двух недель
 * точность никому не нужна: важно, что давно.
 */
fun daysPhrase(days: Int): String = when {
    days <= 1 -> "день"
    days < 7 -> countPhrase(days, "день", "дня", "дней")
    days == 7 -> "неделю"
    days < 14 -> "больше недели"
    days == 14 -> "две недели"
    days < 30 -> "больше двух недель"
    else -> "больше месяца"
}

/**
 * «шесть дней», «14 дней» — словами до десяти, дальше цифрой.
 *
 * Маленькие числа словами читаются как речь, большие словами — как чек.
 *
 * @param feminine род существительного: «одна неделя», «две недели»
 */
fun countPhrase(n: Int, one: String, few: String, many: String, feminine: Boolean = false): String {
    val number = if (n in 1..10) numberWord(n, feminine) else n.toString()
    return "$number ${plural(n, one, few, many)}"
}

/** «второй», «третий» — «третий день подряд». Дальше десяти — «11-й». */
fun ordinal(n: Int): String = ORDINALS.getOrNull(n) ?: "$n-й"

private val ORDINALS = listOf(
    "", "первый", "второй", "третий", "четвёртый", "пятый",
    "шестой", "седьмой", "восьмой", "девятый", "десятый",
)

/**
 * Название еды посреди фразы.
 *
 * «Гречка отварная» в дневнике — заголовок, в реплике — «гречка отварная».
 * Капс и латиница — это бренд, и регистр у него свой. Длинное название режется
 * по слову: «творог мягкий обезжиренный» понятнее, чем название пачки целиком,
 * а уведомлению есть предел длины.
 */
fun foodName(display: String): String {
    val words = display.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.isEmpty()) return ""

    val first = words.first()
    val lowered = if (first.isCapitalizedWord()) first.replaceFirstChar(Char::lowercaseChar) else first
    val all = listOf(lowered) + words.drop(1)

    val kept = mutableListOf<String>()
    var length = 0
    for (word in all) {
        val next = if (kept.isEmpty()) word.length else length + 1 + word.length
        if (next > MAX_FOOD_LENGTH) break
        kept += word
        length = next
    }
    // Одно слово длиннее предела — режем по буквам: молчать о еде хуже.
    if (kept.isEmpty()) return all.first().take(MAX_FOOD_LENGTH)
    return kept.joinToString(" ").tidyTail()
}

private const val MAX_FOOD_LENGTH = 32
private val WHITESPACE = Regex("\\s+")

/** Слово с заглавной и строчными дальше, кириллицей: «Гречка», но не «КЕФИР» и не «Activia». */
private fun String.isCapitalizedWord(): Boolean {
    val letters = filter(Char::isLetter)
    if (letters.isEmpty() || !letters.first().isUpperCase()) return false
    if (letters.any { it in 'a'..'z' || it in 'A'..'Z' }) return false
    return letters.drop(1).none(Char::isUpperCase)
}

/**
 * Хвост обрезанного названия: без висящей запятой и без открытой кавычки.
 *
 * «сырок «Б.Ю.» — обрывок кавычки читается как ошибка, а не как сокращение.
 */
private fun String.tidyTail(): String {
    var text = this
    for ((open, close) in PAIRS) {
        if (text.count { it == open } > text.count { it == close }) {
            text = text.substring(0, text.lastIndexOf(open))
        }
    }
    if (text.count { it == '"' } % 2 == 1) text = text.substring(0, text.lastIndexOf('"'))
    return text.trimEnd { it.isWhitespace() || it in TRAILING }
}

private val PAIRS = listOf('«' to '»', '(' to ')')
private const val TRAILING = ",;:—-–"

/** «пятница» — именительный: «Сегодня пятница». */
fun weekdayName(day: DayOfWeek): String = WEEKDAYS[day.ordinal]

private val WEEKDAYS = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")

/** «09:05». */
fun clock(hour: Int, minute: Int): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

/** Число словами до двенадцати. Род нужен только единице и двойке. */
private fun numberWord(n: Int, feminine: Boolean = false): String = when (n) {
    1 -> if (feminine) "одна" else "один"
    2 -> if (feminine) "две" else "два"
    else -> NUMBERS.getOrNull(n) ?: n.toString()
}

private val NUMBERS = listOf(
    "", "", "", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать",
)

/** Русское счётное слово: 1 день, 2 дня, 5 дней, 11 дней, 21 день. */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val tens = n % 100
    if (tens in 11..14) return many
    return when (n % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}
