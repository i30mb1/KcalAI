package n7.kcalai.personal

import java.time.Instant
import java.time.ZoneId
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.nutriments
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import n7.kcalai.model.parseFoodRef
import n7.kcalai.model.serialize

/**
 * Личная история питания, свёрнутая в счётчики.
 *
 * Весь смысл персонализации держится на одном наблюдении: распределение еды
 * у конкретного человека узкое до неприличия — несколько десятков продуктов
 * покрывают почти все записи. Поэтому счётчики по паре сотен строк дают больше,
 * чем любая модель, обученная на чужих данных.
 *
 * Пересчитывается из `diary_entry` целиком, без агрегатных таблиц: удаление записи
 * обязано немедленно убирать её вклад, а складывать сотни строк дешевле, чем
 * поддерживать инкрементальные счётчики в согласованном состоянии.
 */
class FoodHistory private constructor(
    /** Ключ — [n7.kcalai.model.serialize] от ссылки на продукт. */
    val items: Map<String, Item>,
    /** «Что человек ест после чего» внутри одного дня. Ключи — те же refKey. */
    val transitions: Map<String, Map<String, Int>>,
    /** Часы, в которые начинался каждый приём пищи. Сырьё для детектора пропусков. */
    val mealFirstHours: Map<MealType, List<Int>>,
    val totalEntries: Int,
    private val today: Long,
) {

    /**
     * Один продукт и всё, что о нём известно из истории.
     *
     * Не `data class` намеренно: массивы счётчиков сломали бы сгенерированные
     * `equals`/`hashCode`, а сравнивать эти объекты незачем.
     */
    class Item(
        val ref: FoodRef,
        val displayName: String,
        val nutriments: Nutriments,
        val count: Int,
        val lastDay: Long,
        /** Медиана подтверждённых весов. Устойчива к разовым «съел полкило». */
        val medianGrams: Int,
        /** Индекс — [MealType.ordinal]. */
        val mealCounts: IntArray,
        val hourCounts: IntArray,
    ) {
        fun toCandidate(): FoodCandidate = FoodCandidate(
            ref = ref,
            displayName = displayName,
            nutriments = nutriments,
            servingG = medianGrams,
        )
    }

    val maxCount: Int = items.values.maxOfOrNull { it.count } ?: 0

    fun item(refKey: String): Item? = items[refKey]

    /** Насколько давно продукт ел человек. Свежесть затухает с характерным временем недели. */
    fun daysSinceLast(item: Item): Long = (today - item.lastDay).coerceAtLeast(0)

    /** Доля употреблений продукта, пришедшаяся на этот приём пищи. */
    fun mealShare(item: Item, meal: MealType): Double =
        if (item.count == 0) 0.0 else item.mealCounts[meal.ordinal].toDouble() / item.count

    /**
     * Насколько продукт «подходит» этому часу.
     *
     * Учитывается не только сам час, но и соседние: человек завтракает в 8:00
     * и в 9:00 одним и тем же, и считать это разными привычками бессмысленно.
     */
    fun hourAffinity(item: Item, hour: Int): Double {
        if (item.count == 0) return 0.0
        var near = 0
        for (shift in -1..1) {
            near += item.hourCounts[(hour + shift + HOURS) % HOURS]
        }
        return near.toDouble() / item.count
    }

    /** Вероятность продукта сразу после [prevKey], со сглаживанием Лапласа. */
    fun transitionProbability(prevKey: String?, refKey: String): Double {
        val row = transitions[prevKey ?: return 0.0] ?: return 0.0
        val total = row.values.sum()
        if (total == 0) return 0.0
        return (row[refKey] ?: 0).toDouble() / (total + LAPLACE)
    }

    companion object {

        /** Псевдо-источник перехода: «чем человек обычно начинает этот приём пищи». */
        fun mealStartKey(meal: MealType): String = "^${meal.name}"

        private const val HOURS = 24
        private const val LAPLACE = 1

        /**
         * @param entries история, отсортированная по `dateEpochDay, createdAt` —
         *        именно в этом порядке её отдаёт `DiaryDao.since`
         * @param zone зона, в которой считается час записи: `createdAt` хранится
         *        в UTC-миллисекундах, а привычки у человека в местном времени
         */
        fun from(entries: List<DiaryEntryEntity>, today: Long, zone: ZoneId): FoodHistory {
            val accumulators = LinkedHashMap<String, Accumulator>()
            val transitions = HashMap<String, MutableMap<String, Int>>()
            val mealFirstHours = HashMap<MealType, MutableList<Int>>()

            var prevKey: String? = null
            var prevDay = Long.MIN_VALUE
            val mealsSeenToday = HashSet<MealType>()

            for (entry in entries) {
                if (entry.dateEpochDay != prevDay) {
                    prevKey = null
                    prevDay = entry.dateEpochDay
                    mealsSeenToday.clear()
                }

                val hour = Instant.ofEpochMilli(entry.createdAt).atZone(zone).hour

                // Записи без разбираемой ссылки в моделях не участвуют: повторить
                // такую позицию всё равно нельзя, а счётчики она бы засоряла.
                val ref = parseFoodRef(entry.foodRef)
                if (ref == null) {
                    prevKey = null
                    continue
                }
                val key = ref.serialize()

                accumulators.getOrPut(key) { Accumulator(ref, entry) }.add(entry, hour)

                if (mealsSeenToday.add(entry.meal)) {
                    mealFirstHours.getOrPut(entry.meal) { mutableListOf() } += hour
                    transitions.getOrPut(mealStartKey(entry.meal)) { HashMap() }
                        .merge(key, 1, Int::plus)
                }
                prevKey?.let { previous ->
                    transitions.getOrPut(previous) { HashMap() }.merge(key, 1, Int::plus)
                }
                prevKey = key
            }

            return FoodHistory(
                items = accumulators.mapValues { (_, accumulator) -> accumulator.build() },
                transitions = transitions,
                mealFirstHours = mealFirstHours,
                totalEntries = entries.size,
                today = today,
            )
        }
    }
}

/** Копилка счётчиков одного продукта. Существует только на время сборки истории. */
private class Accumulator(private val ref: FoodRef, first: DiaryEntryEntity) {

    private val mealCounts = IntArray(MealType.entries.size)
    private val hourCounts = IntArray(24)
    private val grams = mutableListOf<Int>()
    private var count = 0
    private var lastDay = Long.MIN_VALUE

    /** Имя и КБЖУ берутся из последней записи: человек мог поправить продукт. */
    private var displayName = first.displayName
    private var nutriments = first.nutriments()

    fun add(entry: DiaryEntryEntity, hour: Int) {
        count++
        mealCounts[entry.meal.ordinal]++
        hourCounts[hour]++
        grams += entry.grams
        if (entry.dateEpochDay >= lastDay) {
            lastDay = entry.dateEpochDay
            displayName = entry.displayName
            nutriments = entry.nutriments()
        }
    }

    fun build(): FoodHistory.Item = FoodHistory.Item(
        ref = ref,
        displayName = displayName,
        nutriments = nutriments,
        count = count,
        lastDay = lastDay,
        medianGrams = grams.median(),
        mealCounts = mealCounts,
        hourCounts = hourCounts,
    )
}

/** Медиана: при чётном числе наблюдений берётся нижнее из двух средних. */
internal fun List<Int>.median(): Int {
    if (isEmpty()) return 0
    val sorted = sorted()
    return sorted[(sorted.size - 1) / 2]
}
