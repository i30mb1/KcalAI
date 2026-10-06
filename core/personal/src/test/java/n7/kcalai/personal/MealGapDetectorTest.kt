package n7.kcalai.personal

import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Идея 4: отрицательное пространство дневника — что человек забыл записать. */
class MealGapDetectorTest {

    /**
     * Человек две недели обедает в 13:00 и ужинает в 19:00. Завтрак не ест никогда.
     *
     * Привычка заканчивается вчера: сегодня в дневнике пусто — ровно тот случай,
     * про который детектор и спрашивает. Положить сегодняшний обед в историю и тут
     * же передать `mealsLoggedToday = emptySet()` значило бы собрать день, которого
     * не бывает, и проверить поведение, которого не будет.
     */
    private fun history(): FoodHistory {
        val entries = mutableListOf<DiaryEntryEntity>()
        var id = 1L
        for (day in TODAY - 14..TODAY - 1) {
            entries += entry(id++, day, 13, MealType.LUNCH, "Суп", SOUP, grams = 350)
            entries += entry(id++, day, 19, MealType.DINNER, "Куриная грудка", CHICKEN, grams = 180)
        }
        return historyOf(entries)
    }

    @Test
    fun `в обычное время обеда вопрос не задаётся`() {
        val gap = MealGapDetector.detect(
            history = history(),
            mealsLoggedToday = emptySet(),
            dismissed = emptySet(),
            nowHour = 13,
            context = contextAt(hour = 13, meal = MealType.LUNCH),
        )

        assertNull("человек может обедать прямо сейчас", gap)
    }

    /**
     * Два часа после обычного времени — нижняя граница терпения.
     *
     * У того, кто ест по часам, MAD близок к нулю, и без этой границы вопрос
     * «ты обедал?» прилетал бы в 13:15, когда человек ещё не донёс тарелку.
     */
    @Test
    fun `вопрос не приходит раньше, чем через два часа после обычного`() {
        fun gapAt(hour: Int) = MealGapDetector.detect(
            history = history(),
            mealsLoggedToday = emptySet(),
            dismissed = emptySet(),
            nowHour = hour,
            context = contextAt(hour = hour, meal = MealType.LUNCH),
        )

        // Обед в 13:00, разброс нулевой: до 15:00 включительно молчим.
        assertNull("в 15:00 ещё рано", gapAt(15))
        assertNotNull("в 16:00 пора спросить", gapAt(16))
    }

    @Test
    fun `через несколько часов после обычного обеда появляется вопрос с подсказками`() {
        val gap = MealGapDetector.detect(
            history = history(),
            mealsLoggedToday = emptySet(),
            dismissed = emptySet(),
            nowHour = 17,
            context = contextAt(hour = 17, meal = MealType.DINNER),
        )

        assertNotNull(gap)
        assertEquals(MealType.LUNCH, gap!!.meal)
        assertEquals(13, gap.typicalHour)
        // Подсказки — для пропущенного обеда, а не для текущего часа.
        assertEquals("Суп", gap.suggestions.first().displayName)
    }

    @Test
    fun `записанный обед вопроса не вызывает`() {
        val gap = MealGapDetector.detect(
            history = history(),
            mealsLoggedToday = setOf(MealType.LUNCH),
            dismissed = emptySet(),
            nowHour = 17,
            context = contextAt(hour = 17, meal = MealType.DINNER),
        )

        assertNull(gap)
    }

    @Test
    fun `ответ «пропустил» закрывает вопрос`() {
        val gap = MealGapDetector.detect(
            history = history(),
            mealsLoggedToday = emptySet(),
            dismissed = setOf(MealType.LUNCH),
            nowHour = 17,
            context = contextAt(hour = 17, meal = MealType.DINNER),
        )

        // Обед закрыт ответом, но до ужина ещё далеко — вопросов больше нет.
        assertNull(gap)
    }

    @Test
    fun `про приём пищи, которого у человека нет, не спрашивают`() {
        val gap = MealGapDetector.detect(
            history = history(),
            mealsLoggedToday = emptySet(),
            dismissed = setOf(MealType.LUNCH, MealType.DINNER),
            nowHour = 23,
            context = contextAt(hour = 23, meal = MealType.SNACK),
        )

        // Завтрак человек не ест ни разу — напоминание о нём было бы упрёком.
        assertNull(gap)
    }

    /**
     * Привычка — это пять раз за две недели, а не один.
     *
     * Иначе единственный завтрак в отпуске заводит ежедневный вопрос про завтрак,
     * и напоминание превращается в упрёк за то, чего человек не делает.
     */
    @Test
    fun `порог привычки — пять употреблений`() {
        fun gapFor(breakfasts: Int): MealGap? {
            val entries = mutableListOf<DiaryEntryEntity>()
            var id = 1L
            for (day in TODAY - 14..TODAY - 1) {
                entries += entry(id++, day, 13, MealType.LUNCH, "Суп", SOUP, grams = 350)
            }
            for (i in 0 until breakfasts) {
                entries += entry(id++, TODAY - 14 + i, 8, MealType.BREAKFAST, "Овсянка", OATMEAL, grams = 250)
            }
            return MealGapDetector.detect(
                history = historyOf(entries),
                mealsLoggedToday = emptySet(),
                dismissed = setOf(MealType.LUNCH),
                nowHour = 12,
                context = contextAt(hour = 12, meal = MealType.LUNCH),
            )
        }

        assertNull("четыре завтрака — ещё не привычка", gapFor(4))
        assertEquals(MealType.BREAKFAST, gapFor(5)?.meal)
    }

    private companion object {
        const val SOUP = 20L
        const val CHICKEN = 21L
        const val OATMEAL = 22L
    }
}
