package n7.kcalai.mascot

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.Mascot
import n7.kcalai.model.owner

/** Сообщение, ушедшее в шторку. */
data class Sent(val topic: Topic, val mascot: Mascot, val at: Instant, val opened: Boolean)

/** О чём написать и кто напишет. */
data class Nudge(val topic: Topic, val mascot: Mascot)

/**
 * Когда персонажи пишут первыми — и когда молчат.
 *
 * Навязчивый персонаж хуже молчаливого: одно лишнее уведомление — и человек
 * выключает их все, вместе с персонажами. Поэтому сначала запреты, потом поводы.
 *
 * Запреты: сон человека (по его привычкам), только что открывал приложение,
 * меньше трёх часов с прошлого сообщения, два сообщения за день, тема уже
 * звучала сегодня. На проигнорированные подряд — реже: после трёх одно в день,
 * после шести — только «ты где пропадаешь».
 *
 * Поводы по важности: пропал, пропущен обычный приём, долго без еды, серия
 * под угрозой, пустой день, белок к вечеру, вчера в цель.
 */
object NudgePlanner {

    fun plan(snapshot: Snapshot, sent: List<Sent>, lastOpenAt: Instant?, now: Instant, zone: TimeZone): Nudge? {
        val facts = snapshot.facts
        val rhythm = snapshot.rhythm
        val hour = facts.now.hour
        if (hour < rhythm.wakeHour || hour >= rhythm.sleepHour) return null
        if (lastOpenAt != null && now - lastOpenAt < AFTER_USE) return null

        val newest = sent.sortedByDescending { it.at }
        if (newest.firstOrNull()?.let { now - it.at < SPACING } == true) return null

        val today = facts.now.date
        val sentToday = newest.filter { it.at.toLocalDateTime(zone).date == today }
        val ignored = newest.filter { now - it.at >= SETTLE }.takeWhile { !it.opened }.size
        val cap = if (ignored >= BACKOFF_AFTER) BACKOFF_CAP else DAILY_CAP
        if (sentToday.size >= cap) return null

        val said = sentToday.mapTo(HashSet()) { it.topic }
        val nudge = choose(snapshot, newest, said, now) ?: return null
        if (ignored >= SILENCE_AFTER && nudge.topic != Topic.COMEBACK) return null
        return nudge
    }

    /** Первый сработавший повод — по важности. Тема, уже звучавшая сегодня, уступает следующей. */
    private fun choose(snapshot: Snapshot, newest: List<Sent>, said: Set<Topic>, now: Instant): Nudge? {
        val facts = snapshot.facts
        val rhythm = snapshot.rhythm
        val hour = facts.now.hour

        // Пропавшему пишут только «ты где» — и только в свои дни. Про обед
        // человеку, которого нет третий день, — не забота, а шум.
        if (facts.daysAway >= 2) {
            val mascot = COMEBACK_DAYS[facts.daysAway] ?: return null
            return Nudge(Topic.COMEBACK, mascot)
                .takeIf { hour >= maxOf(rhythm.wakeHour + 3, COMEBACK_FROM_HOUR) && it.topic !in said }
        }

        val candidates = sequence {
            if (facts.missed != null) {
                val owner = snapshot.suggestions[Topic.MISSED_MEAL]?.candidate?.nutriments?.owner() ?: Mascot.GRAIN
                yield(Nudge(Topic.MISSED_MEAL, owner))
            }
            val gap = facts.minutesSinceLastEntry
            if (facts.todayEntries > 0 && gap != null && gap >= LONG_GAP_MINUTES && rhythm.sleepHour - hour > 2) {
                yield(Nudge(Topic.LONG_GAP, newest.turn(Topic.LONG_GAP, Mascot.GRAIN, Mascot.BUTTER)))
            }
            if (facts.todayEntries == 0 && facts.daysAway == 1) {
                if (facts.streakDays >= STREAK_MIN && hour >= rhythm.sleepHour - 4) yield(Nudge(Topic.STREAK_KEEP, Mascot.EGG))
                if (hour >= maxOf(rhythm.wakeHour + 5, EMPTY_DAY_FROM_HOUR)) {
                    yield(Nudge(Topic.EMPTY_DAY, newest.turn(Topic.EMPTY_DAY, Mascot.GRAIN, Mascot.EGG)))
                }
            }
            val protein = facts.protLeftG
            if (facts.todayEntries > 0 && protein != null && protein >= PROTEIN_MIN_G && hour in rhythm.sleepHour - 5..rhythm.sleepHour - 2) {
                yield(Nudge(Topic.PROTEIN_LEFT, Mascot.EGG))
            }
            val praisedLately = newest.any { it.topic == Topic.YESTERDAY_WIN && now - it.at < WIN_EVERY }
            if (facts.yesterdayOnGoal && facts.todayEntries == 0 && hour in rhythm.wakeHour + 1..WIN_UNTIL_HOUR && !praisedLately) {
                yield(Nudge(Topic.YESTERDAY_WIN, Mascot.BUTTER))
            }
        }
        return candidates.firstOrNull { it.topic !in said }
    }

    /** Кто из двоих писал на тему давнее — тот и пишет: голоса чередуются. */
    private fun List<Sent>.turn(topic: Topic, first: Mascot, second: Mascot): Mascot =
        if (firstOrNull { it.topic == topic }?.mascot == first) second else first

    /** День отсутствия → кто пишет. Между ними — тишина, после двух недель — тоже. */
    private val COMEBACK_DAYS = mapOf(2 to Mascot.GRAIN, 4 to Mascot.BUTTER, 7 to Mascot.EGG, 14 to Mascot.BUTTER)

    private val AFTER_USE = 90.minutes
    private val SPACING = 3.hours

    /** Сообщение младше часа ещё не «проигнорировано»: человек мог просто не успеть. */
    private val SETTLE = 1.hours
    private val WIN_EVERY = 3.days

    private const val DAILY_CAP = 2
    private const val BACKOFF_AFTER = 3
    private const val BACKOFF_CAP = 1
    private const val SILENCE_AFTER = 6
    private const val LONG_GAP_MINUTES = 180
    private const val STREAK_MIN = 3
    private const val PROTEIN_MIN_G = 25
    private const val COMEBACK_FROM_HOUR = 11
    private const val EMPTY_DAY_FROM_HOUR = 13
    private const val WIN_UNTIL_HOUR = 12
}
