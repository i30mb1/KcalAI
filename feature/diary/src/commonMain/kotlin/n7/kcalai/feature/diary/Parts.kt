package n7.kcalai.feature.diary

import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.Nutriments
import n7.kcalai.ui.Macro

/**
 * Главный макрос порции — по граммам, а не по калориям.
 *
 * По калориям победителем почти всегда выходил бы жир: в нём девять килокалорий
 * на грамм против четырёх. Точка на чипсе отвечает на вопрос «из чего это в основном
 * состоит», и граммы отвечают на него честнее.
 */
fun dominantMacro(totals: NutrimentTotals): Macro =
    dominantMacro(totals.protCg, totals.fatCg, totals.carbCg)

/** То же для продукта, у которого ещё нет веса: доли от веса не зависят. */
fun dominantMacro(nutriments: Nutriments): Macro =
    dominantMacro(nutriments.prot100, nutriments.fat100, nutriments.carb100)

private fun dominantMacro(prot: Int, fat: Int, carb: Int): Macro = when {
    prot >= fat && prot >= carb -> Macro.PROTEIN
    fat >= carb -> Macro.FAT
    else -> Macro.CARB
}
