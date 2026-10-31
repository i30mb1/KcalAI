package n7.kcalai.personal

import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.MealType

/** Когда и в какой приём пищи человек что-то ищет. Общий вход всех моделей. */
data class PersonalContext(
    val dateEpochDay: Long,
    val hourOfDay: Int,
    val meal: MealType,
    /** Что добавлено последним — вход модели переходов. `null` в начале дня. */
    val prevRefKey: String? = null,
)

/**
 * Что персонализация даёт резолверу.
 *
 * Узкий интерфейс, а не весь [PersonalRepository]: резолвер не должен уметь писать
 * в журнал и обучать модели, ему нужны ровно две вещи. `null` вместо реализации
 * возвращает приложение к обезличенному поведению — это рабочий режим, а не заглушка.
 */
interface Personalization {

    /** Личные граммовки единиц этого продукта. Пустая карта — данных ещё нет. */
    suspend fun portionUnits(ref: FoodRef): Map<String, Int>

    /** Переупорядочивает выдачу поиска под этого человека, сохраняя её состав. */
    suspend fun rerank(candidates: List<FoodCandidate>, context: PersonalContext): List<FoodCandidate>
}
