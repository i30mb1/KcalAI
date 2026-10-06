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
     * Штраф за продукт, уже съеденный сегодня в другом приёме пищи.
     *
     * Не запрет: кофе дважды в день — это не ошибка, и предложить его вечером
     * человеку, который пил его утром, нормально. Но при прочих равных подсказка
     * про то, чего сегодня ещё не было, полезнее.
     *
     * Штраф больше любого отдельного слагаемого намеренно: без него запись продукта
     * его же и поднимала — свежесть становилась максимальной (`exp(0) = 1`) на самом
     * тяжёлом весе, а счётчик частоты подрастал. Только что записанное предлагалось
     * первым, и ряд «обычно в это время» превращался в эхо последнего ввода.
     */
    private const val REPEAT_PENALTY = 0.40

    /**
     * Что человек, скорее всего, съест сейчас.
     *
     * Съеденное сегодня в этом же приёме пищи не предлагается вовсе: человек только
     * что это записал и видит в ленте прямо над строкой ввода. Съеденное в другом
     * приёме опускается штрафом, но из ряда не исчезает.
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
            .filterNot { (key, _) -> history.eatenToday(key, context.meal) }
            .map { (key, item) -> item to score(history, key, item, context, previous, maxCount) }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (item, _) -> item.toCandidate() }
    }

    /**
     * Насколько давно продукт ел человек — но сегодняшняя запись свежести не даёт.
     *
     * Свежесть отвечает на вопрос «это всё ещё в рационе», и сегодняшняя запись
     * отвечает на него не лучше вчерашней. Разница между ними только в том, что
     * сегодняшнюю человек уже сделал, а значит, предлагать её незачем.
     */
    private fun recency(history: FoodHistory, item: FoodHistory.Item): Double =
        exp(-history.daysSinceLast(item).coerceAtLeast(1) / RECENCY_TAU)

    private fun score(
        history: FoodHistory,
        key: String,
        item: FoodHistory.Item,
        context: PersonalContext,
        previous: String,
        maxCount: Double,
    ): Double {
        val frequency = if (maxCount <= 0.0) 0.0 else ln(1.0 + item.count) / maxCount
        val repeat = if (history.eatenToday(key)) REPEAT_PENALTY else 0.0

        return W_RECENCY * recency(history, item) +
            W_FREQUENCY * frequency +
            W_MEAL * history.mealShare(item, context.meal) +
            W_HOUR * history.hourAffinity(item, context.hourOfDay) +
            W_TRANSITION * history.transitionProbability(previous, key) -
            repeat
    }

    private const val DEFAULT_LIMIT = 6
}
