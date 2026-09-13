package n7.kcalai.model

/**
 * КБЖУ на 100 г продукта в фиксированной точке.
 *
 * @param kcal100 целые килокалории на 100 г
 * @param prot100 белки, сотые грамма на 100 г (12.34 г -> 1234)
 * @param fat100  жиры, сотые грамма на 100 г
 * @param carb100 углеводы, сотые грамма на 100 г
 */
data class Nutriments(
    val kcal100: Int,
    val prot100: Int,
    val fat100: Int,
    val carb100: Int,
)

/**
 * Абсолютные значения для конкретной порции.
 *
 * @param kcal   целые килокалории
 * @param protCg белки, сотые грамма
 * @param fatCg  жиры, сотые грамма
 * @param carbCg углеводы, сотые грамма
 */
data class NutrimentTotals(
    val kcal: Int,
    val protCg: Int,
    val fatCg: Int,
    val carbCg: Int,
) {
    companion object {
        val ZERO: NutrimentTotals = NutrimentTotals(0, 0, 0, 0)
    }
}

/** Пересчитывает значения «на 100 г» в значения для [grams] грамм. */
fun Nutriments.forGrams(grams: Int): NutrimentTotals {
    require(grams >= 0) { "grams must be >= 0, was $grams" }
    return NutrimentTotals(
        kcal = scale(kcal100, grams),
        protCg = scale(prot100, grams),
        fatCg = scale(fat100, grams),
        carbCg = scale(carb100, grams),
    )
}

operator fun NutrimentTotals.plus(other: NutrimentTotals): NutrimentTotals =
    NutrimentTotals(
        kcal = kcal + other.kcal,
        protCg = protCg + other.protCg,
        fatCg = fatCg + other.fatCg,
        carbCg = carbCg + other.carbCg,
    )

/** Умножение в Long, чтобы большие порции не переполняли Int; округление к ближайшему. */
private fun scale(per100: Int, grams: Int): Int {
    val product = per100.toLong() * grams.toLong()
    return ((product + 50L) / 100L).toInt()
}
