package n7.kcalai.model

/**
 * Трое, с кем человек переписывается: каждый — свой макрос и узнаваемый продукт.
 *
 * Здесь только кто они. Как выглядят — в `:core:ui`, что и когда говорят —
 * в `:core:mascot`. Лежат в модели, потому что о них знают и база (кто написал
 * сообщение), и мозг, и экран.
 */
enum class Mascot {
    /** Белок. Яйцо в скорлупе-шапке. */
    EGG,

    /** Жиры. Брусок масла с завитком. */
    BUTTER,

    /** Углеводы. Зёрнышко с хохолком. */
    GRAIN,
}

/** Имя — как его видит человек: в шторке, в подписи к сообщению. */
val Mascot.title: String
    get() = when (this) {
        Mascot.EGG -> "Яйцо"
        Mascot.BUTTER -> "Масло"
        Mascot.GRAIN -> "Зёрнышко"
    }

/**
 * Чей это продукт — по главному макросу.
 *
 * По граммам, а не по калориям. По калориям победителем почти всегда выходил бы
 * жир: в нём девять килокалорий на грамм против четырёх. Вопрос здесь «из чего это
 * в основном состоит», и граммы отвечают на него честнее.
 */
fun Nutriments.owner(): Mascot = owner(prot100, fat100, carb100)

/** То же для порции: доли от веса не зависят. */
fun NutrimentTotals.owner(): Mascot = owner(protCg, fatCg, carbCg)

private fun owner(prot: Int, fat: Int, carb: Int): Mascot = when {
    prot >= fat && prot >= carb -> Mascot.EGG
    fat >= carb -> Mascot.BUTTER
    else -> Mascot.GRAIN
}
