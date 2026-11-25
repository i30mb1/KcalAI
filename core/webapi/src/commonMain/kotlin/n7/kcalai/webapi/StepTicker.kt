package n7.kcalai.webapi

import kotlin.math.max
import kotlin.math.min
import n7.kcalai.model.strideMeters

/*
 * Шаги в окошке идут по одному, как будто приложение отчитывается о каждом. Телефон присылает итог
 * дня раз в секунду; между итогами окошко прибавляет шаги в темпе ходьбы, а свежий итог догоняет плавно.
 * Назад число не отматывается никогда: убежали вперёд — стоим, пока итог не догонит.
 */

/** Насколько вперёд последнего итога можно убежать: пара секунд ходьбы — дальше ждём телефон. */
const val STEP_LEAD_MS = 2_000L

/** Как быстро догоняется отставание сверх темпа: доля разрыва в секунду. */
private const val CATCH_UP_PER_S = 2.5

/**
 * Сколько шагов показать в этом кадре.
 *
 * @param shown показано сейчас (дробное: шаг виден, когда набежала целая единица)
 * @param target последний итог с телефона
 * @param sinceTargetMs сколько прошло с его прихода
 * @param frameMs длина кадра
 */
fun stepsShown(shown: Double, target: Int, sinceTargetMs: Long, stepsPerSecond: Double, frameMs: Long): Double {
    val expected = target + stepsPerSecond * min(sinceTargetMs, STEP_LEAD_MS) / MS_PER_S
    if (expected <= shown) return shown
    val pace = stepsPerSecond + (expected - shown) * CATCH_UP_PER_S
    return min(expected, shown + pace * max(frameMs, 0L) / MS_PER_S)
}

/** Шагов в секунду на этой скорости дорожки. */
fun stepsPerSecond(speedKmh: Double): Double =
    if (speedKmh <= 0.0) 0.0 else speedKmh / KMH_PER_MPS / strideMeters(speedKmh)

private const val MS_PER_S = 1_000.0
private const val KMH_PER_MPS = 3.6
