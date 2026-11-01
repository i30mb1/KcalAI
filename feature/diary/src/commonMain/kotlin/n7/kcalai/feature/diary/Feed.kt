package n7.kcalai.feature.diary

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.totals
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.plus
import n7.kcalai.personal.DayPlan
import n7.kcalai.personal.DayOutline
import n7.kcalai.personal.CLUSTER_GAP_MS
import n7.kcalai.personal.MEAL_WINDOWS
import n7.kcalai.personal.MealGap
import n7.kcalai.personal.mealForHour
import n7.kcalai.repositories.DaySummary
import n7.kcalai.model.Mascot

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

    /**
     * «сегодня», «вчера», «четверг, 29 окт» по центру — чья это переписка.
     *
     * Один на ленту: один день — одна переписка, и прошлые дни открываются
     * каждый своей лентой, а не подклеиваются сверху.
     */
    data class DaySeparator(val label: String) : FeedItem {
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

    /**
     * Сводка дня: приветствие, неделя и раскладка типичного дня. Пока записей нет.
     *
     * @param intro за неделю ни одной записи — персонажи представляются
     * @param needsGoal цели нет — в реплике кнопка, которая её задаёт
     */
    data class Summary(
        val greeting: String,
        val weekLine: String?,
        val outline: DayOutline?,
        val intro: Boolean = false,
        val needsGoal: Boolean = false,
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

    /**
     * Съедено больше цели. Стоит в конце ленты вместо остатка.
     *
     * @param past день прошёл: это итог, и обещать «завтра это учтётся» поздно
     */
    data class Over(val overKcal: Int, val past: Boolean = false) : FeedItem {
        override val key get() = "over"
    }

    /**
     * Прошлый день без записей: не утренняя сводка, а приглашение дописать.
     *
     * «Доброе утро! Сегодня можно 2100 ккал» над вчерашним днём было бы неправдой,
     * а пустая лента — непонятной: сломано или правда ничего.
     */
    data class EmptyDay(val title: String) : FeedItem {
        override val key get() = "empty-day"
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

    /**
     * Запись удалена — и её можно вернуть.
     *
     * Удаление идёт без вопроса «вы уверены?»: на десятой записи за день он
     * дороже самой опечатки. Но без отмены случайный тап стирал запись насовсем,
     * поэтому ответ на удаление несёт кнопку «Вернуть». После возврата реплика
     * уходит из ленты: запись снова стоит в своём пузырьке, и говорить больше не о чем.
     *
     * [entry] — снимок записи целиком: вернуть надо ту же запись, а не похожую.
     */
    data class Deleted(val id: Int, val entry: DiaryEntryEntity, val time: String) : FeedItem {
        override val key get() = "deleted-$id"
    }

    /**
     * Сообщение персонажа: написанное первым в шторку или ответ в ленте.
     *
     * Хранится в базе, а не в сессии, — и поэтому стоит в переписке дня, когда
     * человек откроет приложение по уведомлению: персонаж действительно ему писал.
     *
     * @param minute минута суток, когда оно написано: по ней встаёт между пузырьками
     * @param at когда написано, мс: в одну минуту с записью решает, кто был раньше
     */
    data class Said(
        val id: Long,
        val mascot: Mascot,
        val text: String,
        val time: String,
        val minute: Int,
        val at: Long = Long.MAX_VALUE,
    ) : FeedItem {
        override val key get() = "said-$id"
    }

    /** Персонаж о чём-то спрашивает, и ответить можно одним тапом. */
    data class Ask(val kind: AskKind) : FeedItem {
        override val key get() = "ask-$kind"
    }
}

/** О чём спрашивают персонажи. */
enum class AskKind {
    /** Яйцо: как тебя зовут. */
    NAME,

    /** Зёрнышко: можно ли писать первыми. */
    WRITE,
}

/**
 * Сводка пустого дня.
 *
 * Первая фраза есть всегда, остальные — только если история их подтверждает.
 * Пустая сводка честнее выдуманной: «обычно вы завтракаете в 8:00» на второй
 * день использования это ложь, которую человек сразу заметит.
 *
 * Знакомство — только для того, у кого за неделю нет ни одной записи: каждое утро
 * представляться заново навязчиво, а новичок без него остаётся с пустым экраном
 * и непонятными картинками у реплик. Вернувшийся после недели перерыва услышит
 * знакомство ещё раз, и это не беда: он мог и забыть, кто здесь кто.
 *
 * @param goalKcal цель на сегодня, `null` — её нет
 */
fun daySummary(goalKcal: Int?, week: List<DaySummary>, outline: DayOutline?, hour: Int): FeedItem.Summary {
    val intro = week.all { it.kcal == 0 }
    val head = when {
        intro -> "Привет! Я Яйцо, слежу за белком"
        goalKcal != null -> "${greeting(hour)} Сегодня можно $goalKcal ккал"
        else -> "${greeting(hour)} Цель на сегодня не задана"
    }

    // Считаются только прошедшие дни, в которых что-то записано. Сегодня в счёт
    // не идёт: сводка видна, лишь пока он пуст, и «ноль из цели» засчитывался бы
    // всегда — новичок, только что поставивший цель, слышал «уложились 1 день из 1».
    // Пустой день — тоже не успех: «не записал» и «уложился» — разные вещи,
    // и неделя без единой записи иначе выходила бы «7 дней из 7».
    val judged = week.dropLast(1).filter { it.goalKcal != null && it.kcal > 0 }
    val weekLine = if (judged.isEmpty()) {
        null
    } else {
        val hit = judged.count { it.kcal <= (it.goalKcal ?: 0) }
        // Знаменатель — дни, когда цель вообще была: поставленная позавчера
        // цель не может быть «выполнена 2 дня из 7».
        "За неделю в цель: $hit ${daysWord(hit)} из ${judged.size}"
    }

    return FeedItem.Summary(
        greeting = head,
        weekLine = weekLine,
        outline = outline,
        intro = intro,
        needsGoal = goalKcal == null,
    )
}

/**
 * Кто из троих говорит реплику. `null` — реплика не приложения, а человека.
 *
 * Голос привязан к смыслу: перебор — про жиры, пропущенный приём — про энергию,
 * остаток с недобранным белком — про белок. Всё остальное — служебные ответы,
 * и их ведёт Яйцо: у ленты должен быть один голос по умолчанию, а не жребий.
 */
fun speakerOf(item: FeedItem): Mascot? = when (item) {
    is FeedItem.DaySeparator, is FeedItem.Meal -> null
    is FeedItem.Said -> item.mascot
    is FeedItem.Ask -> when (item.kind) {
        AskKind.NAME -> Mascot.EGG
        AskKind.WRITE -> Mascot.GRAIN
    }
    is FeedItem.Over -> Mascot.BUTTER
    is FeedItem.Gap -> Mascot.GRAIN
    is FeedItem.Remaining -> if (item.plan.remainingProtCg > 0) Mascot.EGG else Mascot.GRAIN
    is FeedItem.Summary,
    is FeedItem.EmptyDay,
    is FeedItem.NotFound,
    is FeedItem.WeightLogged,
    is FeedItem.Reply,
    is FeedItem.Deleted,
    is FeedItem.Outbox -> Mascot.EGG
}

/**
 * Где пузырьку стоять в ленте, минуты от начала суток.
 *
 * Завтрак, обед и ужин держатся своего окна: запись 13:00, перенесённую человеком
 * в завтрак, лента показывала после обеда 12:30 — время у неё осталось обеденное,
 * а смысл уже нет. Время за пределами окна прижимается к его границе, и поздний
 * ужин остаётся после обеда, а ранний завтрак — перед всем. Перекусы стоят там,
 * когда были: своего окна у них нет.
 */
private fun bubbleOrder(group: List<DiaryEntryEntity>, zone: TimeZone): Int {
    val first = localAt(group.first().createdAt, zone)
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
    zone: TimeZone,
    nowMillis: Long,
    activeMeal: MealType? = null,
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
    // В прошлом дне приём выбирает человек, и перекус дописывается к последнему:
    // «остывать» там нечему, время у него — время того дня, см. [pastEntryTime].
    val target = activeMeal ?: mealForHour(localAt(nowMillis, zone).hour, sorted.lastOrNull(), nowMillis)
    val activeGroup = when {
        target != MealType.SNACK -> byMeal[target]
        activeMeal != null -> lastSnack
        else -> lastSnack?.takeIf { nowMillis - it.last().createdAt <= CLUSTER_GAP_MS }
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
fun gapPosition(body: List<FeedItem>, gap: MealGap, zone: TimeZone): Int =
    body.indexOfLast { item ->
        item is FeedItem.Meal && localAt(item.entries.first().createdAt, zone).hour < gap.typicalHour
    } + 1

/**
 * Пузырьки дня и сообщения персонажей — по времени.
 *
 * Сообщение встаёт после всех пузырьков, начавшихся раньше него: «Три часа без
 * еды» в 15:30 стоит после обеда. В одну минуту с записью решают секунды: вопрос
 * из шторки — выше записи, которой на него ответили, а ответ персонажа — ниже.
 */
fun interleave(bubbles: List<FeedItem.Meal>, said: List<FeedItem.Said>, zone: TimeZone): List<FeedItem> {
    val body = mutableListOf<FeedItem>()
    var next = 0
    for (bubble in bubbles) {
        val order = bubbleOrder(bubble.entries, zone)
        val firstAt = bubble.entries.first().createdAt
        while (next < said.size && (said[next].minute < order || said[next].minute == order && said[next].at < firstAt)) {
            body += said[next++]
        }
        body += bubble
    }
    while (next < said.size) body += said[next++]
    return body
}

/**
 * В какой приём писать прошлый день, пока человек не выбрал сам.
 *
 * В прошлый день возвращаются чаще всего за забытым приёмом — поэтому первый
 * основной, которого в том дне нет. Все три на месте — значит, дописывают
 * перекус.
 */
fun defaultPastMeal(entries: List<DiaryEntryEntity>): MealType {
    val logged = entries.mapTo(HashSet()) { it.meal }
    return MAIN_MEALS.firstOrNull { it !in logged } ?: MealType.SNACK
}

private val MAIN_MEALS = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)

/** Час приёма прошлого дня, когда его времени взять неоткуда: середина окна. */
val PAST_MEAL_HOURS: Map<MealType, Int> = mapOf(
    MealType.BREAKFAST to 8,
    MealType.LUNCH to 13,
    MealType.DINNER to 19,
    MealType.SNACK to 16,
)

/**
 * Время записи, дописанной в прошлый день.
 *
 * Не «сейчас»: ужин, дописанный утром, получил бы девять утра, встал бы в ленте
 * не на своё место и научил бы модели привычек, что ужинают в девять. Поэтому —
 * минута после последней записи того же приёма, чтобы встать следом, а если
 * приёма в том дне ещё нет — его обычный час.
 */
fun pastEntryTime(day: LocalDate, meal: MealType, entries: List<DiaryEntryEntity>, zone: TimeZone): Long {
    entries.filter { it.meal == meal }.maxOfOrNull { it.createdAt }?.let { return it + MINUTE_MS }
    return LocalDateTime(day, LocalTime(PAST_MEAL_HOURS.getValue(meal), 0)).toInstant(zone).toEpochMilliseconds()
}

private const val MINUTE_MS = 60_000L

private fun localAt(millis: Long, zone: TimeZone) =
    kotlin.time.Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
