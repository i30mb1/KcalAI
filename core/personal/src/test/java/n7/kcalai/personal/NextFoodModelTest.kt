package n7.kcalai.personal

import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Идея 1: дневник как язык — предсказание следующего продукта. */
class NextFoodModelTest {

    /**
     * Две недели человек завтракает овсянкой и запивает кофе, а ужинает курицей.
     *
     * Это и есть тот самый узкий личный словарь, на котором биграммы работают
     * там, где большой модели не хватило бы данных.
     */
    private fun history(): FoodHistory {
        val entries = mutableListOf<DiaryEntryEntity>()
        var id = 1L
        for (day in TODAY - 13..TODAY) {
            entries += entry(id++, day, 8, MealType.BREAKFAST, "Овсянка", OATMEAL, grams = 250)
            entries += entry(id++, day, 8, MealType.BREAKFAST, "Кофе с молоком", COFFEE, grams = 200)
            entries += entry(id++, day, 19, MealType.DINNER, "Куриная грудка", CHICKEN, grams = 180)
        }
        return historyOf(entries)
    }

    @Test
    fun `утром предлагается завтрак, а не ужин`() {
        val predictions = NextFoodModel.predict(
            history = history(),
            context = contextAt(hour = 8, meal = MealType.BREAKFAST),
        )

        assertEquals("Овсянка", predictions.first().displayName)
        assertTrue(
            "ужин не должен подниматься на завтраке",
            predictions.indexOfFirst { it.displayName == "Куриная грудка" } > 0,
        )
    }

    @Test
    fun `после овсянки предлагается кофе — это и есть модель переходов`() {
        val predictions = NextFoodModel.predict(
            history = history(),
            context = contextAt(
                hour = 8,
                meal = MealType.BREAKFAST,
                prevRefKey = genericKey(OATMEAL),
            ),
        )

        assertEquals("Кофе с молоком", predictions.first().displayName)
    }

    @Test
    fun `граммы берутся из личной медианы, а не из таблицы порций`() {
        val predictions = NextFoodModel.predict(
            history = history(),
            context = contextAt(hour = 8, meal = MealType.BREAKFAST),
        )

        assertEquals(250, predictions.first { it.displayName == "Овсянка" }.servingG)
    }

    @Test
    fun `на пустой истории модель молчит, а не выдумывает`() {
        val predictions = NextFoodModel.predict(
            history = historyOf(listOf(entry(1, TODAY, 8, MealType.BREAKFAST, "Овсянка", OATMEAL))),
            context = contextAt(hour = 8, meal = MealType.BREAKFAST),
        )

        assertTrue(predictions.isEmpty())
    }

    private companion object {
        const val OATMEAL = 1L
        const val COFFEE = 2L
        const val CHICKEN = 3L
    }
}
