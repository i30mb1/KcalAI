package n7.kcalai.feature.diary

import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.Nutriments
import n7.kcalai.model.owner
import n7.kcalai.ui.Macro
import n7.kcalai.ui.mascot.macro

/**
 * Главный макрос порции — тот же, что у её хозяина-персонажа.
 *
 * Правило «по граммам, а не по калориям» живёт в [owner]: точка на чипсе
 * и персонаж, который на него запрыгивает, обязаны совпадать.
 */
fun dominantMacro(totals: NutrimentTotals): Macro = totals.owner().macro

/** То же для продукта, у которого ещё нет веса: доли от веса не зависят. */
fun dominantMacro(nutriments: Nutriments): Macro = nutriments.owner().macro
