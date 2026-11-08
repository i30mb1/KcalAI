package n7.kcalai.model

/**
 * КБЖУ на 100 г продукта в фиксированной точке.
 *
 * @param kcal100 целые килокалории на 100 г
 * @param prot100 белки, сотые грамма на 100 г (12.34 г -> 1234)
 * @param fat100  жиры, сотые грамма на 100 г
 * @param carb100 углеводы, сотые грамма на 100 г
 * @param fiber100 клетчатка (пищевые волокна), сотые грамма на 100 г; `null` — неизвестна.
 *        Отдельно от углеводов, как в русских таблицах и на этикетках: углеводы её не включают.
 */
data class Nutriments(
    val kcal100: Int,
    val prot100: Int,
    val fat100: Int,
    val carb100: Int,
    val fiber100: Int? = null,
)

/**
 * Клетчатка порции или дня.
 *
 * Отдельно от [NutrimentTotals], потому что у неё есть то, чего у БЖУ нет: она бывает
 * неизвестна. У котлеты из справочника клетчатка — ноль, а у пачки без строки
 * «пищевые волокна» её просто нет, и сложить это как ноль значило бы занизить день
 * и упрекать за недобор, которого могло не быть.
 *
 * @param cg известная часть, сотые грамма
 * @param unknown сколько продуктов в сумме без данных о клетчатке
 */
data class FiberTotal(val cg: Int, val unknown: Int) {

    /** Все продукты знали свою клетчатку: [cg] — вся клетчатка, а не нижняя граница. */
    val complete: Boolean get() = unknown == 0

    operator fun plus(other: FiberTotal): FiberTotal = FiberTotal(cg + other.cg, unknown + other.unknown)

    companion object {
        val ZERO: FiberTotal = FiberTotal(0, 0)
    }
}

/** Клетчатка [grams] грамм продукта: известная — пересчитанной, неизвестная — пробелом. */
fun Nutriments.fiberFor(grams: Int): FiberTotal {
    require(grams >= 0) { "grams must be >= 0, was $grams" }
    return fiber100?.let { FiberTotal(scale(it, grams), unknown = 0) } ?: FiberTotal(0, unknown = 1)
}

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
