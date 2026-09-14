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

    /**
     * Очередь отправки на сервер: что скопилось и кнопка «отправить».
     *
     * Воркеры шлют сами, но молча — а человеку надо видеть, что ушло.
     * Пузырёк есть, только пока очередь не пуста.
     */
    data class Outbox(val count: OutboxCount, val sending: Boolean) : FeedItem {
        override val key get() = "outbox"
    }

    /** Ответ на действие: «Отправлено: 2 сессии». Живёт до конца сессии экрана, как [WeightLogged]. */
    data class Reply(val id: Int, val text: String, val time: String) : FeedItem {
        override val key get() = "reply-$id"
    }
}

/**
 * Приём пищи по времени суток.
 *
 * Окна с зазорами, а не встык: в 11:20 человек может доедать завтрак и может
 * обедать, и угадывать здесь нечем. Поэтому между окнами решает не расписание,
 * а сам дневник — запись, сделанную сразу после предыдущей, логично считать
 * её продолжением. Час без записей означает, что это уже отдельная еда.
 *
 * Первая еда дня перекусом не бывает: в 11:00 на пустом дневнике это поздний
 * завтрак, в 16:00 — поздний обед. Перекус — это то, что между приёмами,
 * а между чем и чем ему быть, если приёмов ещё не было.
 *
 * @param lastEntry последняя запись за сегодня, `null` — сегодня ещё не ели
 */
fun mealForHour(hour: Int, lastEntry: DiaryEntryEntity?, nowMillis: Long): MealType {
    MEAL_WINDOWS.entries.firstOrNull { (_, window) -> hour in window }?.let { return it.key }
    if (lastEntry == null) return lastMainMealBefore(hour)
    return lastEntry
        .takeIf { nowMillis - it.createdAt <= CLUSTER_GAP_MS }
        ?.meal
        ?: MealType.SNACK
}

/** Ближайший прошедший основной приём. Ночью — ужин: он последний в сутках. */
private fun lastMainMealBefore(hour: Int): MealType =
    MEAL_WINDOWS.entries.lastOrNull { (_, window) -> window.last < hour }?.key ?: MealType.DINNER

/** Часы, в которые приём пищи — свой. У перекуса своего времени нет. */
private val MEAL_WINDOWS: Map<MealType, IntRange> = mapOf(
    MealType.BREAKFAST to 5..10,
    MealType.LUNCH to 12..15,
    MealType.DINNER to 17..21,
)

/**
 * Где пузырьку стоять в ленте, минуты от начала суток.
 *
 * Завтрак, обед и ужин держатся своего окна: запись 13:00, перенесённую человеком
 * в завтрак, лента показывала после обеда 12:30 — время у неё осталось обеденное,
 * а смысл уже нет. Время за пределами окна прижимается к его границе, и поздний
 * ужин остаётся после обеда, а ранний завтрак — перед всем. Перекусы стоят там,
 * когда были: своего окна у них нет.
 */
private fun bubbleOrder(group: List<DiaryEntryEntity>, zone: ZoneId): Int {
    val first = Instant.ofEpochMilli(group.first().createdAt).atZone(zone)
    val minuteOfDay = first.hour * 60 + first.minute
    val window = MEAL_WINDOWS[group.first().meal] ?: return minuteOfDay
    return minuteOfDay.coerceIn(window.first * 60, window.last * 60 + 59)
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

    return groups.sortedBy { bubbleOrder(it, zone) }.map { group ->
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
