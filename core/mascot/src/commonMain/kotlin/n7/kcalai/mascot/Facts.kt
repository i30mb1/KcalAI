package n7.kcalai.mascot

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import n7.kcalai.mascot.voice.clock
import n7.kcalai.mascot.voice.countPhrase
import n7.kcalai.mascot.voice.daysPhrase
import n7.kcalai.mascot.voice.foodName
import n7.kcalai.mascot.voice.hoursPhrase
import n7.kcalai.mascot.voice.ordinal
import n7.kcalai.mascot.voice.weekdayName
import n7.kcalai.model.MealType
import n7.kcalai.model.Sex
import n7.kcalai.model.genitive
import n7.kcalai.model.title

/** Обычный приём, которого сегодня нет, и чем человек его обычно закрывает. */
data class MissedMeal(
    val meal: MealType,
    /** Час, в который человек обычно ест этот приём. */
    val usualHour: Int,
    val usualFood: String?,
    val usualGrams: Int?,
)

/** Жаворонок завтракает до семи, сова ужинает после девяти. */
enum class Chronotype { LARK, OWL }

/** 5–11, 12–16, 17–22, 23–4. */
enum class PartOfDay { MORNING, DAY, EVENING, NIGHT }

/** Сколько знакомы: тон теплеет со временем. До трёх дней, до месяца, дольше. */
enum class Bond { NEW, FAMILIAR, CLOSE }

/**
 * Всё, о чём персонаж может сказать, — посчитанное приложением.
 *
 * Персонаж не выдумывает ни одной цифры: каждая берётся отсюда. Поле `null` —
 * этого факта сегодня нет, и вариант, которому он нужен, просто не выбирается.
 */
data class Facts(
    /** Местное время человека. */
    val now: LocalDateTime,
    val name: String? = null,
    val sex: Sex? = null,
    /** Дней с первой записи. */
    val daysTogether: Int = 0,
    val minutesSinceLastEntry: Int? = null,
    val lastFood: String? = null,
    val lastEntryAt: LocalTime? = null,
    val todayEntries: Int = 0,
    val missed: MissedMeal? = null,
    /** Сколько белка осталось до цели, граммы. */
    val protLeftG: Int? = null,
    /** Белковое из обычной еды человека — чем закрыть остаток. */
    val protIdea: String? = null,
    /** Дней подряд с записями, кончая вчерашним. */
    val streakDays: Int = 0,
    /** Дней с последней записи. Ноль — сегодня уже писал. */
    val daysAway: Int = 0,
    /** Самая частая еда в истории. */
    val favorite: String? = null,
    val yesterdayOnGoal: Boolean = false,
    val chronotype: Chronotype? = null,
    /** Сколько человека не было в приложении, минуты. */
    val absentMinutes: Int? = null,
    /** Еда, о которой речь прямо сейчас: только что записанная. */
    val food: String? = null,
    /** Сколько дней подряд человек ест [food], включая сегодня. */
    val foodDaysInRow: Int = 0,
) {
    val partOfDay: PartOfDay
        get() = when (now.hour) {
            in 5..11 -> PartOfDay.MORNING
            in 12..16 -> PartOfDay.DAY
            in 17..22 -> PartOfDay.EVENING
            else -> PartOfDay.NIGHT
        }

    val weekend: Boolean
        get() = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY

    val bond: Bond
        get() = when {
            daysTogether <= 3 -> Bond.NEW
            daysTogether <= 30 -> Bond.FAMILIAR
            else -> Bond.CLOSE
        }
}

/**
 * Значение плейсхолдера `{key}` — так, как оно встанет во фразу.
 *
 * `null` — факта нет, и вариант с этим плейсхолдером не выбирается. Пороги здесь же:
 * «уже меньше часа без еды» и «серия из одного дня» — не повод для реплики.
 */
internal fun Facts.value(key: String): String? = when (key) {
    "name" -> name
    "gap" -> minutesSinceLastEntry?.takeIf { it >= 60 }?.let(::hoursPhrase)
    "last" -> lastFood?.let(::foodName)
    "lastTime" -> lastEntryAt?.let { clock(it.hour, it.minute) }
    "meal" -> missed?.meal?.title?.lowercase()
    "mealGen" -> missed?.meal?.genitive
    "usualTime" -> missed?.usualHour?.let { clock(it, 0) }
    "usual" -> missed?.usualFood?.let(::foodName)
    "usualGrams" -> missed?.usualGrams?.takeIf { it > 0 }?.let { "$it г" }
    "protLeft" -> protLeftG?.takeIf { it > 0 }?.let { "$it г" }
    "protIdea" -> protIdea?.let(::foodName)
    "streak" -> streakDays.takeIf { it >= 2 }?.let { countPhrase(it, "день", "дня", "дней") }
    "away" -> daysAway.takeIf { it >= 1 }?.let(::daysPhrase)
    "absent" -> absentMinutes?.takeIf { it >= 60 }?.let { if (it < DAY_MINUTES) hoursPhrase(it) else daysPhrase(it / DAY_MINUTES) }
    "fav" -> favorite?.let(::foodName)
    "food" -> food?.let(::foodName)
    "foodRun" -> foodDaysInRow.takeIf { it >= 2 }?.let { "${ordinal(it)} день подряд" }
    "today" -> weekdayName(now.dayOfWeek)
    else -> null
}

/** Все плейсхолдеры, которые знает [value]: опечатка в варианте ловится тестом, а не человеком. */
internal val KNOWN_KEYS = setOf(
    "name", "gap", "last", "lastTime", "meal", "mealGen", "usualTime", "usual", "usualGrams",
    "protLeft", "protIdea", "streak", "away", "absent", "fav", "food", "foodRun", "today",
)

private const val DAY_MINUTES = 24 * 60
