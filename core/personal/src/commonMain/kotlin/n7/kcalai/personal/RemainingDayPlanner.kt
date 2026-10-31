package n7.kcalai.personal

import kotlin.math.abs
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.forGrams
import n7.kcalai.model.plus
import n7.kcalai.model.serialize

/** Один вариант добора: что съесть и что из этого получится. */
data class PlanOption(
    val items: List<FoodCandidate>,
    val totals: NutrimentTotals,
)

/** Сколько осталось до цели и чем это закрыть. */
data class DayPlan(
    val remainingKcal: Int,
    val remainingProtCg: Int,
    val options: List<PlanOption>,
)

/**
 * Идея 5: что съесть на оставшиеся калории.
 *
 * Это не «совет по питанию» и не генерация: набор блюд ограничен тем, что человек
 * уже ест, порции — его собственными медианами, а задача решается точным перебором,
 * а не эвристикой. Приложение не придумывает еду, оно решает задачу об укладке
 * рюкзака на сорока предметах.
 *
 * Перебираются одиночные варианты и пары — 40 + 780 комбинаций, доли миллисекунды.
 * Троек нет намеренно: «съешьте эти три вещи» уже не предложение, а меню.
 */
object RemainingDayPlanner {

    /** Сколько самых частых продуктов участвует в переборе. */
    private const val POOL = 40

    /** Допуск по калориям: вариант на 10% мимо остатка ещё попадает в цель. */
    private const val KCAL_TOLERANCE = 0.10

    /** Ниже этого остатка добирать нечего — день фактически закрыт. */
    private const val MIN_REMAINING_KCAL = 100

    /**
     * Штраф за продукт, уже съеденный сегодня.
     *
     * Не запрет: добавить вторую порцию того же — совершенно нормальный ответ.
     * Но при прочих равных разнообразный вариант полезнее.
     */
    private const val REPEAT_PENALTY = 0.35

    /** Бонус за то, что продукт человек обычно ест именно в этот приём пищи. */
    private const val MEAL_BONUS = 0.25

    /**
     * @param remainingKcal сколько калорий осталось до цели
     * @param remainingProtCg сколько белка осталось, сотые грамма
     * @param eatenToday ключи продуктов, уже съеденных сегодня
     * @return до трёх вариантов, лучший первым, либо `null` — добирать нечего
     */
    fun plan(
        history: FoodHistory,
        remainingKcal: Int,
        remainingProtCg: Int,
        eatenToday: Set<String>,
        context: PersonalContext,
        limit: Int = DEFAULT_LIMIT,
    ): DayPlan? {
        if (remainingKcal < MIN_REMAINING_KCAL) return null

        val pool = history.items.values
            .sortedByDescending { it.count }
            .take(POOL)
            .map { it.toCandidate() }
            .filter { (it.servingG ?: 0) > 0 }
        if (pool.isEmpty()) return null

        val lowerBound = remainingKcal * (1 - KCAL_TOLERANCE)
        val upperBound = remainingKcal * (1 + KCAL_TOLERANCE)
        val scored = mutableListOf<Pair<PlanOption, Double>>()

        fun consider(items: List<FoodCandidate>) {
            val totals = items.fold(NutrimentTotals.ZERO) { sum, candidate ->
                sum + candidate.nutriments.forGrams(candidate.servingG ?: 0)
            }
            if (totals.kcal < lowerBound || totals.kcal > upperBound) return
            scored += PlanOption(items, totals) to score(history, items, totals, remainingKcal, remainingProtCg, eatenToday, context)
        }

        for (first in pool.indices) {
            consider(listOf(pool[first]))
            for (second in first + 1 until pool.size) {
                consider(listOf(pool[first], pool[second]))
            }
        }

        val options = scored
            .sortedByDescending { (_, score) -> score }
            .map { (option, _) -> option }
            .distinctBy { option -> option.items.map { it.ref.serialize() }.toSet() }
            .take(limit)
        if (options.isEmpty()) return null

        return DayPlan(
            remainingKcal = remainingKcal,
            remainingProtCg = remainingProtCg,
            options = options,
        )
    }

    /**
     * Чем вариант лучше другого.
     *
     * Главный критерий — белок: калории уже загнаны в допуск жёстким фильтром,
     * а вот недобор белка это единственная часть цели, которую действительно
     * стоит оптимизировать.
     */
    private fun score(
        history: FoodHistory,
        items: List<FoodCandidate>,
        totals: NutrimentTotals,
        remainingKcal: Int,
        remainingProtCg: Int,
        eatenToday: Set<String>,
        context: PersonalContext,
    ): Double {
        val protein = if (remainingProtCg <= 0) {
            0.0
        } else {
            (totals.protCg.toDouble() / remainingProtCg).coerceAtMost(1.0)
        }
        val kcalFit = 1.0 - abs(totals.kcal - remainingKcal).toDouble() / remainingKcal

        var repeats = 0.0
        var mealFit = 0.0
        for (candidate in items) {
            val key = candidate.ref.serialize()
            if (key in eatenToday) repeats += REPEAT_PENALTY
            history.item(key)?.let { mealFit += MEAL_BONUS * history.mealShare(it, context.meal) }
        }

        return protein + kcalFit + mealFit - repeats
    }

    private const val DEFAULT_LIMIT = 3
}
