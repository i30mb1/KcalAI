package n7.kcalai.model

enum class MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK,
}

/** «Завтрак» — подпись пузырька, чипс выбора приёма, фраза персонажа. */
val MealType.title: String
    get() = when (this) {
        MealType.BREAKFAST -> "Завтрак"
        MealType.LUNCH -> "Обед"
        MealType.DINNER -> "Ужин"
        MealType.SNACK -> "Перекус"
    }

/** «обеда» — «сегодня обеда в дневнике нет». */
val MealType.genitive: String
    get() = when (this) {
        MealType.BREAKFAST -> "завтрака"
        MealType.LUNCH -> "обеда"
        MealType.DINNER -> "ужина"
        MealType.SNACK -> "перекуса"
    }
