package n7.kcalai.personal

import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.MealType

/**
 * Приём пищи по времени суток.
 *
 * Окна с зазорами, а не встык: в 11:20 человек может доедать завтрак и может
 * обедать, и угадывать здесь нечем. Поэтому между окнами решает не расписание,
 * а сам дневник — запись, сделанную сразу после предыдущей, логично считать
 * её продолжением. Час без записей означает, что это уже отдельная еда.
 *
 * Первая еда дня перекусом не бывает: в 11:00 на пустом дневнике это поздний
 * завтрак, в 16:00 — поздний обед. Перекус — это то, что между приёмами,
 * а между чем и чем ему быть, если приёмов ещё не было.
 *
 * @param lastEntry последняя запись за сегодня, `null` — сегодня ещё не ели
 */
fun mealForHour(hour: Int, lastEntry: DiaryEntryEntity?, nowMillis: Long): MealType {
    MEAL_WINDOWS.entries.firstOrNull { (_, window) -> hour in window }?.let { return it.key }
    if (lastEntry == null) return lastMainMealBefore(hour)
    return lastEntry
        .takeIf { nowMillis - it.createdAt <= CLUSTER_GAP_MS }
        ?.meal
        ?: MealType.SNACK
}

/** Ближайший прошедший основной приём. Ночью — ужин: он последний в сутках. */
private fun lastMainMealBefore(hour: Int): MealType =
    MEAL_WINDOWS.entries.lastOrNull { (_, window) -> window.last < hour }?.key ?: MealType.DINNER

/** Часы, в которые приём пищи — свой. У перекуса своего времени нет. */
val MEAL_WINDOWS: Map<MealType, IntRange> = mapOf(
    MealType.BREAKFAST to 5..10,
    MealType.LUNCH to 12..15,
    MealType.DINNER to 17..21,
)

/** Разрыв, после которого еда считается отдельной. Он же — срок годности активного пузырька. */
const val CLUSTER_GAP_MS = 60 * 60 * 1000L
