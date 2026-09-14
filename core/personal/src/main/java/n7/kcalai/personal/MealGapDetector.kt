package n7.kcalai.personal

import kotlin.math.abs
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.MealType

/**
 * Пропущенный приём пищи и чем его, вероятно, закрыть.
 *
 * @param typicalHour час, в который человек обычно ест этот приём
 */
data class MealGap(
    val meal: MealType,
    val typicalHour: Int,
    val suggestions: List<FoodCandidate>,
)

/**
 * Идея 4: отрицательное пространство дневника.
 *
 * Все модели выше предсказывают, что человек съест. Эта — что он забыл записать.
 * Главная причина, по которой люди бросают трекеры, не в том, что те неточно считают,
 * а в том, что записывать забывают, и через неделю дневник дырявый и бесполезный.
 *
 * Ничего не советует и не оценивает: только задаёт вопрос и заранее готовит ответы
 * на один тап. «Ты обедал?» — [«вчерашний обед»] [«другое»] [«пропустил»].
 */
object MealGapDetector {

    /**
     * Сколько раз за окно приём пищи должен встретиться, чтобы считаться привычкой.
     *
     * Человек, который не завтракает, не должен получать вопрос про завтрак каждый день —
     * это ровно тот случай, когда напоминание превращается в упрёк.
     *
     * Публичный, потому что порог общий с раскладкой типичного дня: «обычный завтрак»
     * в сводке и вопрос про пропущенный завтрак обязаны появляться из одних данных.
     */
    const val MIN_OCCURRENCES = 5

    /**
     * Насколько час должен уйти за обычное время, прежде чем спрашивать.
     *
     * Берётся максимум из разброса самого человека и этой константы: у того, кто ест
     * по часам, MAD близок к нулю, и без нижней границы вопрос прилетал бы в 13:15.
     */
    private const val MIN_DELAY_HOURS = 2

    /**
     * @param mealsLoggedToday приёмы пищи, по которым сегодня уже есть записи
     * @param dismissed приёмы, про которые человек сегодня ответил «пропустил»
     * @param nowHour текущий час в местной зоне
     * @return самый ранний незакрытый приём пищи, либо `null`
     */
    fun detect(
        history: FoodHistory,
        mealsLoggedToday: Set<MealType>,
        dismissed: Set<MealType>,
        nowHour: Int,
        context: PersonalContext,
    ): MealGap? {
        for (meal in MealType.entries) {
            if (meal in mealsLoggedToday || meal in dismissed) continue

            val hours = history.mealFirstHours[meal].orEmpty()
            if (hours.size < MIN_OCCURRENCES) continue

            val typical = hours.median()
            val spread = maxOf(hours.medianAbsoluteDeviation(typical), MIN_DELAY_HOURS)
            if (nowHour <= typical + spread) continue

            val suggestions = NextFoodModel.predict(
                history = history,
                // Предсказываем для ПРОПУЩЕННОГО приёма, а не для текущего часа:
                // в 16:00 человеку нужен его обычный обед, а не полдник.
                context = context.copy(meal = meal, hourOfDay = typical, prevRefKey = null),
                limit = SUGGESTION_LIMIT,
            )
            if (suggestions.isEmpty()) continue

            return MealGap(meal = meal, typicalHour = typical, suggestions = suggestions)
        }
        return null
    }

    private const val SUGGESTION_LIMIT = 3
}

/**
 * Медианное абсолютное отклонение — разброс, устойчивый к выбросам.
 *
 * Обычное стандартное отклонение раздул бы один поздний ужин в отпуске,
 * и вопрос про пропущенный ужин перестал бы приходить вовсе.
 */
internal fun List<Int>.medianAbsoluteDeviation(center: Int): Int =
    if (isEmpty()) 0 else map { abs(it - center) }.median()
