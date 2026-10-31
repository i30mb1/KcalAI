package n7.kcalai.database

import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.Nutriments
import n7.kcalai.model.forGrams

/**
 * Снимок КБЖУ записи как он лёг в дневник.
 *
 * Живёт рядом с сущностью, а не в репозиториях: над историей считают и модели
 * персонализации, а они лежат ниже репозиториев. Две одинаковые проекции
 * в разных слоях разъехались бы при первой же правке.
 */
fun DiaryEntryEntity.nutriments(): Nutriments =
    Nutriments(kcal100 = kcal100, prot100 = prot100, fat100 = fat100, carb100 = carb100)

/** КБЖУ записи для её веса — для строки списка и для сумм за день. */
fun DiaryEntryEntity.totals(): NutrimentTotals = nutriments().forGrams(grams)
