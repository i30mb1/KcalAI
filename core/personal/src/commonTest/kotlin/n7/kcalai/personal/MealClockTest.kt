package n7.kcalai.personal

import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Какой приём пищи сейчас.
 *
 * Правило общее для ленты, для записи и для персонажей: кнопка «Записать»
 * в шторке обязана положить еду туда же, куда её положил бы набор в строке.
 */
class MealClockTest {

    private fun at(hour: Int, minute: Int = 0): Long = (hour * 60L + minute) * 60_000L

    private fun entry(id: Long, hour: Int, minute: Int = 0, meal: MealType) = DiaryEntryEntity(
        id = id,
        dateEpochDay = 0,
        meal = meal,
        displayName = "Еда $id",
        grams = 100,
        kcal100 = 100,
        prot100 = 0,
        fat100 = 0,
        carb100 = 0,
        source = EntrySource.TEXT,
        foodRef = "generic:$id",
        createdAt = at(hour, minute),
    )

    @Test
    fun `внутри окон приём определяется расписанием`() {
        assertEquals(MealType.BREAKFAST, mealForHour(8, lastEntry = null, nowMillis = at(8)))
        assertEquals(MealType.LUNCH, mealForHour(13, lastEntry = null, nowMillis = at(13)))
        assertEquals(MealType.DINNER, mealForHour(19, lastEntry = null, nowMillis = at(19)))
    }

    /**
     * Границы окон — не округление, а сами зазоры.
     *
     * Окна стоят с зазорами намеренно: в 11:00 человек может доедать завтрак
     * и может обедать. Съехавшая граница съедает зазор, и правило «решает дневник»
     * перестаёт работать ровно там, где оно и нужно.
     */
    @Test
    fun `границы окон стоят на своих часах`() {
        // Завтрак 5–10, обед 12–15, ужин 17–21; между ними зазоры.
        val cold = entry(1, hour = 0, meal = MealType.SNACK)

        assertEquals(MealType.BREAKFAST, mealForHour(10, cold, nowMillis = at(10)))
        assertEquals(MealType.SNACK, mealForHour(11, cold, nowMillis = at(11)))
        assertEquals(MealType.LUNCH, mealForHour(12, cold, nowMillis = at(12)))
        assertEquals(MealType.LUNCH, mealForHour(15, cold, nowMillis = at(15)))
        assertEquals(MealType.SNACK, mealForHour(16, cold, nowMillis = at(16)))
        assertEquals(MealType.DINNER, mealForHour(17, cold, nowMillis = at(17)))
        assertEquals(MealType.DINNER, mealForHour(21, cold, nowMillis = at(21)))
        assertEquals(MealType.SNACK, mealForHour(22, cold, nowMillis = at(22)))
    }

    @Test
    fun `в зазоре между окнами продолжается недавняя еда`() {
        // 11:20 — завтрак ещё доедают, если последняя запись была полчаса назад.
        val breakfast = entry(1, hour = 10, minute = 50, meal = MealType.BREAKFAST)

        assertEquals(MealType.BREAKFAST, mealForHour(11, breakfast, nowMillis = at(11, 20)))
    }

    @Test
    fun `в зазоре после часа тишины — перекус`() {
        val breakfast = entry(1, hour = 9, meal = MealType.BREAKFAST)
        val dinner = entry(2, hour = 19, meal = MealType.DINNER)

        assertEquals(MealType.SNACK, mealForHour(11, breakfast, nowMillis = at(11, 30)))
        assertEquals(MealType.SNACK, mealForHour(23, dinner, nowMillis = at(23)))
    }

    @Test
    fun `первая еда дня перекусом не бывает`() {
        // На пустом дневнике зазор отдаётся ближайшему прошедшему приёму.
        assertEquals(MealType.BREAKFAST, mealForHour(11, lastEntry = null, nowMillis = at(11)))
        assertEquals(MealType.LUNCH, mealForHour(16, lastEntry = null, nowMillis = at(16)))
        assertEquals(MealType.DINNER, mealForHour(23, lastEntry = null, nowMillis = at(23)))
        assertEquals(MealType.DINNER, mealForHour(2, lastEntry = null, nowMillis = at(2)))
    }
}
