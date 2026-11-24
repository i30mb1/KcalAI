package n7.kcalai.model

/*
 * Граммы словами — одни на приложение и страницу дневника в браузере:
 * «4,6» в шапке телефона и во вкладке обязаны совпадать.
 */

/** Сотые грамма -> «4,6». Целые значения показываются без хвоста. */
fun formatCentigrams(cg: Int): String {
    val whole = cg / 100
    val tenths = (cg % 100) / 10
    return if (tenths == 0) whole.toString() else "$whole,$tenths"
}

/** «200 г» / «1,2 кг» — вес записи. */
fun formatGrams(grams: Int): String =
    if (grams >= 1000) "${formatCentigrams(grams / 10)} кг" else "$grams г"

/**
 * Клетчатка целыми граммами — как числа плиток.
 *
 * Если у части продуктов клетчатка неизвестна, известное — нижняя граница: «≥8».
 * Неизвестна у всех — показывать нечего, и `null`: «0» было бы неправдой.
 */
fun formatFiber(fiber: FiberTotal): String? {
    val grams = fiber.cg / CENTIGRAMS_IN_GRAM
    return when {
        fiber.complete -> "$grams"
        fiber.cg > 0 -> "≥$grams"
        else -> null
    }
}

private const val CENTIGRAMS_IN_GRAM = 100
