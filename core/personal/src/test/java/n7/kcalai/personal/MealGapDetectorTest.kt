package n7.kcalai.personal

import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Идея 4: отрицательное пространство дневника — что человек забыл записать. */
class MealGapDetectorTest {

    /** Человек две недели обедает в 13:00 и ужинает в 19:00. Завтрак не ест никогда. */
    private fun history(): FoodHistory {
        val entries = mutableListOf<DiaryEntryEntity>()
        var id = 1L
        for (day in TODAY - 13..TODAY) {
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
        assertTrue(gap == null || gap.meal != MealType.BREAKFAST)
    }

    private companion object {
        const val SOUP = 20L
        const val CHICKEN = 21L
    }
}
