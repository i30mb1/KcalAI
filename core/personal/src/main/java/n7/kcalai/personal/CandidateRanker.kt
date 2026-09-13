package n7.kcalai.personal

import kotlin.math.exp
import kotlin.math.ln
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.serialize

/**
 * Идея 2: ранжирование выдачи под конкретного человека.
 *
 * Базовый порядок задаёт FTS5: сначала точные совпадения алиаса, потом префиксные,
 * внутри — по популярности. Это порядок «для среднего пользователя», а среднего
 * пользователя не существует: один под «кофе» всегда имеет в виду латте с овсяным
 * молоком, другой — чёрный без сахара.
 *
 * Модель — логистическая регрессия на восьми признаках, обучаемая на единственном
 * источнике разметки, который у приложения есть: на выборах самого человека.
 * Веса занимают восемь строк в таблице.
 */
object CandidateRanker {

    /**
     * Имена весов. Идут в таблицу как есть, поэтому переименование = потеря обучения —
     * но не поломка: пропавший вес читается как ноль.
     */
    val FEATURES: List<String> = listOf(
        "base",      // позиция в выдаче FTS
        "exact",     // совпал алиас целиком
        "generic",   // генерик, а не брендовый товар
        "own",       // продукт заведён самим пользователем
        "frequency", // как часто человек это ест
        "meal",      // доля употреблений в этом приёме пищи
        "recency",   // как недавно ел
        "hour",      // насколько подходит этому часу
    )

    /**
     * Стартовые веса: важна только позиция в базовой выдаче.
     *
     * До первого обучения ранжирование в точности совпадает с нынешним — это и есть
     * решение проблемы холодного старта. Отдельного порога «включить после N событий»
     * не нужно, персонализация проступает сама по мере накопления выборов.
     */
    val INITIAL: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    private const val RECENCY_TAU = 7.0

    /** Скорость обучения и L2. Событий десятки, поэтому шаг крупный, а регуляризация слабая. */
    private const val LEARNING_RATE = 0.05
    private const val L2 = 1e-4

    /** Веса зажимаются, чтобы одна аномальная серия выборов не перекосила модель навсегда. */
    private const val WEIGHT_LIMIT = 5.0

    /**
     * Признаки одного кандидата.
     *
     * @param baseIndex позиция, которую кандидату дал FTS-поиск
     */
    fun features(
        history: FoodHistory,
        ref: FoodRef,
        exactMatch: Boolean,
        baseIndex: Int,
        context: PersonalContext,
    ): DoubleArray {
        val item = history.item(ref.serialize())
        val maxCount = ln(1.0 + history.maxCount)

        return doubleArrayOf(
            1.0 / (1.0 + baseIndex),
            if (exactMatch) 1.0 else 0.0,
            if (ref is FoodRef.Generic) 1.0 else 0.0,
            if (ref is FoodRef.User) 1.0 else 0.0,
            if (item == null || maxCount <= 0.0) 0.0 else ln(1.0 + item.count) / maxCount,
            if (item == null) 0.0 else history.mealShare(item, context.meal),
            if (item == null) 0.0 else exp(-history.daysSinceLast(item) / RECENCY_TAU),
            if (item == null) 0.0 else history.hourAffinity(item, context.hourOfDay),
        )
    }

    fun score(weights: DoubleArray, features: DoubleArray): Double {
        var sum = 0.0
        for (index in features.indices) sum += weights[index] * features[index]
        return sum
    }

    /**
     * Переупорядочивает выдачу, не меняя её состава.
     *
     * Состав трогать нельзя: выбросив кандидата, модель лишила бы человека возможности
     * её поправить, и ошибка ранжирования стала бы самоподтверждающейся.
     */
    fun rerank(
        weights: DoubleArray,
        history: FoodHistory,
        candidates: List<FoodCandidate>,
        context: PersonalContext,
    ): List<FoodCandidate> {
        if (candidates.size < 2) return candidates

        return candidates
            .mapIndexed { index, candidate ->
                val features = features(history, candidate.ref, candidate.exactMatch, index, context)
                Triple(candidate, score(weights, features), index)
            }
            // Исходный индекс — тайбрейкер: при равных оценках порядок FTS сохраняется,
            // иначе выдача переставлялась бы от запроса к запросу без причины.
            .sortedWith(compareByDescending<Triple<FoodCandidate, Double, Int>> { it.second }
                .thenBy { it.third })
            .map { (candidate, _, _) -> candidate }
    }

    /**
     * Один шаг обучения по одному событию — попарная логистическая модель (RankNet).
     *
     * Пары строятся только из кандидатов, стоявших ВЫШЕ выбранного: они и есть
     * настоящая ошибка ранжирования. Кандидаты ниже выбранного ошибкой не являются —
     * человек до них просто не дошёл, и учить на них значило бы учить на шуме.
     *
     * @param shown признаки показанных кандидатов в порядке показа
     * @param pickedIndex позиция выбранного среди [shown]
     */
    fun train(weights: DoubleArray, shown: List<DoubleArray>, pickedIndex: Int) {
        val positive = shown.getOrNull(pickedIndex) ?: return

        for (index in 0 until pickedIndex) {
            val negative = shown[index]
            var margin = 0.0
            for (feature in weights.indices) {
                margin += weights[feature] * (positive[feature] - negative[feature])
            }
            val gradient = 1.0 - sigmoid(margin)

            for (feature in weights.indices) {
                val step = gradient * (positive[feature] - negative[feature]) - L2 * weights[feature]
                weights[feature] = (weights[feature] + LEARNING_RATE * step)
                    .coerceIn(-WEIGHT_LIMIT, WEIGHT_LIMIT)
            }
        }
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
