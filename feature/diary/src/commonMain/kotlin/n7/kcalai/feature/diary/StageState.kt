package n7.kcalai.feature.diary

import n7.kcalai.mascot.CueKind
import n7.kcalai.model.Mascot

/** Облачко над персонажем: живёт секунды и в ленту не попадает. */
data class SpeechUi(val id: Long, val mascot: Mascot, val text: String)

/**
 * Что сцене отыграть: хозяину еды — подпрыгнуть, всем — отпраздновать, встретить.
 *
 * [id] — чтобы два одинаковых сигнала подряд сыграли оба: второй тык тоже тык.
 */
data class CueUi(val id: Long, val kind: CueKind, val mascot: Mascot?)

/**
 * Сколько живёт облачко: прочитать на бегу.
 *
 * Две с половиной секунды и по сорок миллисекунд на букву, не дольше пяти:
 * дольше облачко уже не реплика, а табличка.
 */
fun speechDurationMs(text: String): Long = (2_500L + 40L * text.length).coerceAtMost(5_000L)

/**
 * Записанная еда точно закрыла цель дня: было меньше 95%, стало 95…105%.
 *
 * Только переход, а не «в цели»: праздник случается раз, на той записи, которая
 * до цели довела, а не на каждой следующей.
 */
fun reachedGoal(kcalBefore: Int, kcalAfter: Int, goalKcal: Int): Boolean {
    val low = goalKcal * 0.95
    val high = goalKcal * 1.05
    return kcalBefore < low && kcalAfter >= low && kcalAfter <= high
}
