package n7.kcalai.model

/** Продукт, готовый к добавлению в дневник: чем он является и какой у него КБЖУ. */
data class FoodCandidate(
    val ref: FoodRef,
    val displayName: String,
    val nutriments: Nutriments,
    /**
     * Типичная разовая порция. У генерика — `default_portion_g`, у товара — `serving_g`.
     * Подставляется, когда в тексте нет веса («немного гречки»).
     */
    val servingG: Int?,
    /** Совпал алиас целиком, а не отдельные слова. Поднимает confidence парсера. */
    val exactMatch: Boolean = false,
)
