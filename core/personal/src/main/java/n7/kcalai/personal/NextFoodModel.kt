package n7.kcalai.personal

import kotlin.math.exp
import kotlin.math.ln
import n7.kcalai.model.FoodCandidate

/**
 * Идея 1: дневник как язык.
 *
 * Обычная языковая модель предсказывает следующее слово. Здесь словарь — не пятьдесят
 * тысяч слов, а полсотни продуктов, которые человек действительно ест, поэтому
 * биграммы над личной историей работают там, где трансформеру не хватило бы данных.
 * Модель весит килобайты, обучается за время сборки [FoodHistory] и отвечает
 * поиском по хэш-таблице.
 *
 * Задача у неё ровно одна: избавить от набора текста самый частый случай —
 * «то же, что всегда в это время».
 */
object NextFoodModel {

    /**
     * Веса слагаемых. Подобраны по смыслу, а не обучением: обучать пять чисел
     * не на чем — сигнала «человек не выбрал предсказание» у нас нет, есть только
     * «выбрал». Их место — здесь, в одном списке, чтобы правка была одной строкой.
     */
    private const val W_RECENCY = 0.35
    private const val W_FREQUENCY = 0.25
    private const val W_MEAL = 0.20
    private const val W_HOUR = 0.10
    private const val W_TRANSITION = 0.10

    /** Характерное время забывания, дни: недельный ритм питания заметно сильнее суточного. */
    private const val RECENCY_TAU = 7.0

    /**
     * Меньше этого числа записей предсказывать нечего.
     *
     * Порог не про качество модели, а про честность: по двум записям она уверенно
     * предложит ровно эти две, и человек решит, что приложение сломано.
     */
    private const val MIN_HISTORY = 5

    /**
     * Что человек, скорее всего, съест сейчас.
     *
     * @return до [limit] кандидатов с граммами из личной медианы, лучший первым.
     *         Пустой список означает «данных мало» — показывать надо обычную подсказку.
     */
    fun predict(
        history: FoodHistory,
        context: PersonalContext,
        limit: Int = DEFAULT_LIMIT,
    ): List<FoodCandidate> {
        if (history.totalEntries < MIN_HISTORY) return emptyList()

        // Если предыдущего блюда нет, спрашиваем «чем обычно начинается этот приём пищи».
        val previous = context.prevRefKey ?: FoodHistory.mealStartKey(context.meal)
        val maxCount = ln(1.0 + history.maxCount)

        return history.items.entries
            .map { (key, item) -> item to score(history, key, item, context, previous, maxCount) }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (item, _) -> item.toCandidate() }
    }

    private fun score(
        history: FoodHistory,
        key: String,
        item: FoodHistory.Item,
        context: PersonalContext,
        previous: String,
        maxCount: Double,
    ): Double {
        val recency = exp(-history.daysSinceLast(item) / RECENCY_TAU)
        val frequency = if (maxCount <= 0.0) 0.0 else ln(1.0 + item.count) / maxCount

        return W_RECENCY * recency +
            W_FREQUENCY * frequency +
            W_MEAL * history.mealShare(item, context.meal) +
            W_HOUR * history.hourAffinity(item, context.hourOfDay) +
            W_TRANSITION * history.transitionProbability(previous, key)
    }

    private const val DEFAULT_LIMIT = 6
}
