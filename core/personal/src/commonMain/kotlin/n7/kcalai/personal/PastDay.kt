package n7.kcalai.personal

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.MealType

/*
 * Запись в прошлый день — одни правила на приложение и страницу дневника в браузере.
 */

/**
 * В какой приём писать прошлый день, пока человек не выбрал сам.
 *
 * В прошлый день возвращаются чаще всего за забытым приёмом — поэтому первый
 * основной, которого в том дне нет. Все три на месте — значит, дописывают
 * перекус.
 */
fun defaultPastMeal(entries: List<DiaryEntryEntity>): MealType {
    val logged = entries.mapTo(HashSet()) { it.meal }
    return MAIN_MEALS.firstOrNull { it !in logged } ?: MealType.SNACK
}

private val MAIN_MEALS = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)

/** Час приёма прошлого дня, когда его времени взять неоткуда: середина окна. */
val PAST_MEAL_HOURS: Map<MealType, Int> = mapOf(
    MealType.BREAKFAST to 8,
    MealType.LUNCH to 13,
    MealType.DINNER to 19,
    MealType.SNACK to 16,
)

/**
 * Время записи, дописанной в прошлый день.
 *
 * Не «сейчас»: ужин, дописанный утром, получил бы девять утра, встал бы в ленте
 * не на своё место и научил бы модели привычек, что ужинают в девять. Поэтому —
 * минута после последней записи того же приёма, чтобы встать следом, а если
 * приёма в том дне ещё нет — его обычный час.
 */
fun pastEntryTime(day: LocalDate, meal: MealType, entries: List<DiaryEntryEntity>, zone: TimeZone): Long {
    entries.filter { it.meal == meal }.maxOfOrNull { it.createdAt }?.let { return it + MINUTE_MS }
    return LocalDateTime(day, LocalTime(PAST_MEAL_HOURS.getValue(meal), 0)).toInstant(zone).toEpochMilliseconds()
}

private const val MINUTE_MS = 60_000L
