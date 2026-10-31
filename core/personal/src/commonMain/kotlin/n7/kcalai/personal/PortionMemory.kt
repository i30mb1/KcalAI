package n7.kcalai.personal

import n7.kcalai.database.PersonalDao
import n7.kcalai.database.PortionSampleEntity
import n7.kcalai.model.FoodRef
import n7.kcalai.model.serialize

/**
 * Идея 3: сколько грамм у ЭТОГО человека весит порция.
 *
 * Общая таблица `portion_unit` отвечает на вопрос «сколько весит горсть орехов»
 * и отвечает правильно — в среднем. Но «тарелка супа» у разных людей отличается
 * вдвое, «кусок хлеба» зависит от того, какой хлеб человек покупает, а «стакан»
 * — от того, какой стакан стоит у него в шкафу. Спорить с этим бесполезно:
 * правильный ответ тот, который человек подтверждает раз за разом.
 *
 * Вторая по величине систематическая ошибка трекеров после кулинарного состояния —
 * и лечится она без единого мегабайта модели.
 */
class PortionMemory(private val dao: PersonalDao) {

    /**
     * Личные граммовки единиц продукта — накладываются поверх табличных.
     *
     * Пустая карта означает «наблюдений мало», а не «данных нет»: подмешивать
     * личную порцию после одного подтверждения опаснее, чем не подмешивать вовсе.
     */
    suspend fun unitsFor(ref: FoodRef): Map<String, Int> {
        val samples = dao.portionSamples(ref.serialize(), SAMPLE_WINDOW)
        if (samples.isEmpty()) return emptyMap()

        return samples
            .groupBy { it.unit }
            .mapNotNull { (unit, group) ->
                if (group.size < MIN_SAMPLES) return@mapNotNull null
                unit to group.take(MEDIAN_WINDOW).map { it.gramsPerUnit }.median()
            }
            .toMap()
    }

    /**
     * Запоминает подтверждённый вес.
     *
     * @param unit каноническая единица; [n7.kcalai.personal.PortionMemory.PORTION_UNIT]
     *        для случая «единица не названа» — то есть для типичной порции продукта
     * @param gramsPerUnit вес ОДНОЙ единицы, а не всей позиции: «2 яйца по 60 г»
     *        это наблюдение про 60, а не про 120
     */
    suspend fun remember(ref: FoodRef, unit: String, gramsPerUnit: Int, now: Long) {
        if (gramsPerUnit !in MIN_GRAMS..MAX_GRAMS) return

        val refKey = ref.serialize()
        dao.insertPortionSample(
            PortionSampleEntity(
                refKey = refKey,
                unit = unit,
                gramsPerUnit = gramsPerUnit,
                createdAt = now,
            )
        )
        dao.trimPortionSamples(refKey, SAMPLE_WINDOW)
    }

    companion object {
        /**
         * Псевдоединица «типичная порция». Обязана совпадать с `Units.PORTION`
         * из парсера: там «тарелка» и «порция» схлопываются в неё же.
         */
        const val PORTION_UNIT = "порция"

        /** С какого наблюдения личная граммовка считается достовернее табличной. */
        private const val MIN_SAMPLES = 3

        /** По скольким последним наблюдениям берётся медиана. */
        private const val MEDIAN_WINDOW = 10

        /** Сколько наблюдений на продукт хранится. Остальное прополкой уходит. */
        private const val SAMPLE_WINDOW = 30

        // Абсурдные веса в статистику не пускаем: один промах по клавиатуре
        // не должен сдвинуть медиану, вокруг которой строится весь ввод.
        private const val MIN_GRAMS = 1
        private const val MAX_GRAMS = 3000
    }
}
