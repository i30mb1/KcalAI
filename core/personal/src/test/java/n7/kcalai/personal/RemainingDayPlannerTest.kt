package n7.kcalai.personal

import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Идея 5: чем закрыть остаток дня. */
class RemainingDayPlannerTest {

    /**
     * Четыре продукта, которые человек ест регулярно, с их личными порциями:
     *
     * | Продукт | Порция | Ккал | Белок |
     * |---|---|---|---|
     * | Творог  | 200 г | 300 | 36 г |
     * | Йогурт  | 300 г | 300 | 33 г |
     * | Яблоко  | 200 г | 100 | 0,8 г |
     * | Орехи   | 100 г | 600 | 15 г |
     *
     * Творог и йогурт стоят одинаковых калорий и различаются почти только белком —
     * на этой паре и видно, что именно оптимизирует перебор.
     */
    private fun history(): FoodHistory {
        val entries = mutableListOf<DiaryEntryEntity>()
        var id = 1L
        for (day in TODAY - 13..TODAY) {
            entries += entry(id++, day, 21, MealType.SNACK, "Творог", COTTAGE, grams = 200, kcal100 = 150, prot100 = 1800)
            entries += entry(id++, day, 20, MealType.SNACK, "Йогурт", YOGURT, grams = 300, kcal100 = 100, prot100 = 1100)
            entries += entry(id++, day, 16, MealType.SNACK, "Яблоко", APPLE, grams = 200, kcal100 = 50, prot100 = 40)
            entries += entry(id++, day, 11, MealType.SNACK, "Орехи", NUTS, grams = 100, kcal100 = 600, prot100 = 1500)
        }
        return historyOf(entries)
    }

    @Test
    fun `вариант укладывается в остаток по калориям`() {
        val plan = RemainingDayPlanner.plan(
            history = history(),
            remainingKcal = 300,
            remainingProtCg = 4000,
            eatenToday = emptySet(),
            context = contextAt(hour = 21, meal = MealType.SNACK),
        )

        assertNotNull(plan)
        assertTrue("варианты должны быть", plan!!.options.isNotEmpty())
        plan.options.forEach { option ->
            assertTrue(
                "вариант на ${option.totals.kcal} ккал не укладывается в остаток 300",
                option.totals.kcal in 270..330,
            )
        }
    }

    @Test
    fun `при равных калориях выигрывает вариант с белком`() {
        val plan = RemainingDayPlanner.plan(
            history = history(),
            remainingKcal = 300,
            remainingProtCg = 4000,
            eatenToday = emptySet(),
            context = contextAt(hour = 21, meal = MealType.SNACK),
        )

        // Творог и йогурт стоят одинаковых 300 ккал, но белка 36 г против 33 —
        // калории уже загнаны в допуск фильтром, оптимизировать остаётся белок.
        assertEquals("Творог", plan!!.options.first().items.first().displayName)
    }

    @Test
    fun `закрытый день добирать не предлагают`() {
        val plan = RemainingDayPlanner.plan(
            history = history(),
            remainingKcal = 40,
            remainingProtCg = 0,
            eatenToday = emptySet(),
            context = contextAt(hour = 21, meal = MealType.SNACK),
        )

        assertNull(plan)
    }

    @Test
    fun `съеденное сегодня опускается, но не исчезает`() {
        val withoutRepeat = RemainingDayPlanner.plan(
            history = history(),
            remainingKcal = 300,
            remainingProtCg = 4000,
            eatenToday = emptySet(),
            context = contextAt(hour = 21, meal = MealType.SNACK),
        )!!
        val withRepeat = RemainingDayPlanner.plan(
            history = history(),
            remainingKcal = 300,
            remainingProtCg = 4000,
            eatenToday = setOf(genericKey(COTTAGE)),
            context = contextAt(hour = 21, meal = MealType.SNACK),
        )!!

        assertEquals("Творог", withoutRepeat.options.first().items.first().displayName)
        // Съеденное сегодня уступает первое место...
        assertEquals("Йогурт", withRepeat.options.first().items.first().displayName)
        // ...но из списка не пропадает: вторая порция того же — нормальный ответ,
        // поэтому здесь штраф, а не запрет.
        assertTrue(
            "творог должен остаться доступным вариантом",
            withRepeat.options.any { option -> option.items.any { it.displayName == "Творог" } },
        )
    }

    private companion object {
        const val COTTAGE = 30L
        const val YOGURT = 31L
        const val APPLE = 32L
        const val NUTS = 33L
    }
}
