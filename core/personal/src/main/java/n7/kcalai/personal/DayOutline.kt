package n7.kcalai.personal

import n7.kcalai.model.MealType

/** Один приём пищи в типичном дне человека. */
data class TypicalMeal(
    val meal: MealType,
    /** Час, в который приём обычно начинается. Медиана по истории. */
    val hour: Int,
    /** Сколько в нём обычно калорий. Медиана по дням, в которые приём был. */
    val kcal: Int,
)

/**
 * Раскладка типичного дня — то, с чего начинается день в ленте.
 *
 * Ни совет, ни план: это пересказ того, что человек уже делает. «Обычно завтрак
 * у вас около 08:00 и это ~450 ккал» — единственная фраза, которую приложение
 * может сказать утром, не выдумывая и не поучая.
 *
 * Приёмы — только в хронологическом порядке и только те, что стали привычкой.
 */
data class DayOutline(val meals: List<TypicalMeal>) {
    val totalKcal: Int = meals.sumOf { it.kcal }
}

object DayOutlineModel {

    /**
     * @return раскладка или `null`, если привычек ещё не набралось
     */
    fun build(history: FoodHistory): DayOutline? {
        val meals = MealType.entries.mapNotNull { meal ->
            val hours = history.mealFirstHours[meal].orEmpty()
            // Порог общий с детектором пропуска, и это важнее экономии: человек,
            // которому не задают вопрос про завтрак, не должен читать про свой
            // «обычный завтрак» — иначе приложение противоречит само себе.
            if (hours.size < MealGapDetector.MIN_OCCURRENCES) return@mapNotNull null

            val kcal = history.mealKcal[meal].orEmpty()
            if (kcal.isEmpty()) return@mapNotNull null

            TypicalMeal(meal = meal, hour = hours.median(), kcal = kcal.median())
        }.sortedBy { it.hour }

        return if (meals.isEmpty()) null else DayOutline(meals)
    }
}
