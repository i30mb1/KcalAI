package n7.kcalai.model

/**
 * Первый незаписанный основной приём, а если записаны все — перекус. За ним в прошлый
 * день обычно и возвращаются; одно правило на приложение и страницу дневника.
 */
fun firstUnloggedMeal(logged: Collection<MealType>): MealType =
    MAIN_MEALS.firstOrNull { it !in logged } ?: MealType.SNACK

private val MAIN_MEALS = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)
