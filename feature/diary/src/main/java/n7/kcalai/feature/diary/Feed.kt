package n7.kcalai.feature.diary

import java.time.Instant
import java.time.ZoneId
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.totals
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.plus
import n7.kcalai.personal.DayPlan
import n7.kcalai.personal.DayOutline
import n7.kcalai.personal.MealGap

/**
 * Готовая лента дня.
 *
 * Экран её только рисует: ни группировки, ни выбора формулировок, ни решения
 * «что показать вместо остатка» в композициях нет. Причина простая — всё это
 * правила, а правила, размазанные по `@Composable`, невозможно ни прочитать
 * целиком, ни изменить в одном месте.
 */
sealed interface FeedItem {

    /** Ключ для `LazyColumn`: от него зависит, что переиспользуется, а что анимируется. */
    val key: String

    /** «сегодня» по центру. Единственный разделитель: прошлых дней в ленте пока нет. */
    data object DaySeparator : FeedItem {
        override val key get() = "separator"
    }

    /**
     * Исходящий пузырёк: то, что записал человек.
     *
     * @param active сюда попадёт запись, добавленная прямо сейчас
     * @param time время первой записи пузырька
     */
    data class Meal(
        override val key: String,
        val meal: MealType,
        val entries: List<DiaryEntryEntity>,
        val totals: NutrimentTotals,
        val time: String,
        val active: Boolean,
    ) : FeedItem

    /** Сводка дня: приветствие, неделя и раскладка типичного дня. Пока записей нет. */
    data class Summary(
        val greeting: String,
        val weekLine: String?,
        val outline: DayOutline?,
    ) : FeedItem {
        override val key get() = "summary"
    }

    /** Идея 4: приём пищи, который человек, похоже, забыл записать. */
    data class Gap(val gap: MealGap) : FeedItem {
        override val key get() = "gap-${gap.meal}"
    }

    /** Идея 5: остаток и чем его закрыть. [mealTitle] — «на ужин», по текущему часу. */
    data class Remaining(val plan: DayPlan, val mealTitle: String) : FeedItem {
        override val key get() = "remaining"
    }

    /** Съедено больше цели. Стоит в конце ленты вместо остатка. */
    data class Over(val overKcal: Int) : FeedItem {
        override val key get() = "over"
    }

    /** Набранное не находится. Ответ репликой, а не строкой в композере. */
    data class NotFound(val query: String) : FeedItem {
        override val key get() = "not-found"
    }

    /**
     * Вес записан. Реплика живёт до конца сессии экрана.
     *
     * [id] — порядковый номер реплики. Ключ по весу и времени схлопнул бы два
     * взвешивания одной минутой в один элемент, а `LazyColumn` на повторяющемся
     * ключе падает.
     */
    data class WeightLogged(val id: Int, val grams: Int, val time: String) : FeedItem {
        override val key get() = "weight-$id"
    }
}

/**
 * Приём пищи по времени суток.
 *
 * Окна с зазорами, а не встык: в 11:20 человек может доедать завтрак и может
 * обедать, и угадывать здесь нечем. Поэтому между окнами решает не расписание,
 * а сам дневник — запись, сделанную сразу после предыдущей, логично считать
 * её продолжением. Час без записей означает, что это уже отдельная еда.
 */
fun mealForHour(hour: Int, lastEntry: DiaryEntryEntity?, nowMillis: Long): MealType = when (hour) {
    in 5..10 -> MealType.BREAKFAST
    in 12..15 -> MealType.LUNCH
    in 17..21 -> MealType.DINNER
    else -> lastEntry
        ?.takeIf { nowMillis - it.createdAt <= CLUSTER_GAP_MS }
        ?.meal
        ?: MealType.SNACK
}

/**
 * Записи дня, сгруппированные в пузырьки.
 *
 * Завтрак, обед и ужин — по одному пузырьку на тип, даже если человек возвращался
 * к обеду через два часа: это один приём пищи, разнесённый во времени, и разрывать
 * его значило бы показывать два обеда.
 *
 * Перекусы — наоборот: у них нет «своего» времени, и два перекуса с разрывом
 * в четыре часа это две разные еды. Поэтому перекусы бьются на кластеры по часу.
 */
fun groupIntoBubbles(
    entries: List<DiaryEntryEntity>,
    zone: ZoneId,
    nowMillis: Long,
): List<FeedItem.Meal> {
    if (entries.isEmpty()) return emptyList()

    val sorted = entries.sortedWith(compareBy({ it.createdAt }, { it.id }))
    val groups = mutableListOf<MutableList<DiaryEntryEntity>>()
    val byMeal = HashMap<MealType, MutableList<DiaryEntryEntity>>()
    var lastSnack: MutableList<DiaryEntryEntity>? = null

    for (entry in sorted) {
        if (entry.meal == MealType.SNACK) {
            val cluster = lastSnack?.takeIf { entry.createdAt - it.last().createdAt <= CLUSTER_GAP_MS }
            if (cluster != null) {
                cluster += entry
            } else {
                val fresh = mutableListOf(entry)
                groups += fresh
                lastSnack = fresh
            }
        } else {
            val existing = byMeal[entry.meal]
            if (existing != null) {
                existing += entry
            } else {
                val fresh = mutableListOf(entry)
                byMeal[entry.meal] = fresh
                groups += fresh
            }
        }
    }

    // Куда попадёт следующая запись: приём по правилу времени, а для перекуса —
    // последний кластер, и только пока он не остыл. Иначе подписи нет вовсе:
    // запись создаст новый пузырёк, и указывать на существующий было бы ложью.
    val hour = Instant.ofEpochMilli(nowMillis).atZone(zone).hour
    val activeMeal = mealForHour(hour, sorted.lastOrNull(), nowMillis)
    val activeGroup = if (activeMeal == MealType.SNACK) {
        lastSnack?.takeIf { nowMillis - it.last().createdAt <= CLUSTER_GAP_MS }
    } else {
        byMeal[activeMeal]
    }

    return groups.map { group ->
        FeedItem.Meal(
            key = "meal-${group.first().id}",
            meal = group.first().meal,
            entries = group,
            totals = group.fold(NutrimentTotals.ZERO) { acc, entry -> acc + entry.totals() },
            time = formatTime(group.first().createdAt, zone),
            active = group === activeGroup,
        )
    }
}

/**
 * Куда встаёт вопрос про пропущенный приём.
 *
 * После последнего пузырька, начавшегося раньше типичного часа: пропущенный обед
 * должен стоять между завтраком и ужином, а не в конце ленты, где он читался бы
 * как вопрос про то, что происходит сейчас.
 */
fun gapPosition(bubbles: List<FeedItem.Meal>, gap: MealGap, zone: ZoneId): Int =
    bubbles.indexOfLast { bubble ->
        Instant.ofEpochMilli(bubble.entries.first().createdAt).atZone(zone).hour < gap.typicalHour
    } + 1

/** Разрыв, после которого еда считается отдельной. Он же — срок годности активного пузырька. */
const val CLUSTER_GAP_MS = 60 * 60 * 1000L
