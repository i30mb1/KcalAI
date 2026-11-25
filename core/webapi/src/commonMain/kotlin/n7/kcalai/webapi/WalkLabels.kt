package n7.kcalai.webapi

import kotlin.math.roundToInt

/*
 * Подписи прогулок на странице дневника — как на экране беговой дорожки: шаги с разрядами,
 * путь в километрах с сотыми, темп с десятой, запятая вместо точки.
 */

/** «08:05» — время дня из минут от полуночи. */
fun clockLabel(minute: Int): String = "${pad(minute / MINUTES_PER_HOUR)}:${pad(minute % MINUTES_PER_HOUR)}"

/** «2,14 км». */
fun kmLabel(meters: Int): String = "${meters / METERS_PER_KM},${pad(meters % METERS_PER_KM / METERS_PER_HUNDREDTH)} км"

/** «4,0 км/ч». */
fun speedLabel(kmh: Double): String {
    val tenths = (kmh * TENTHS).roundToInt()
    return "${tenths / TENTHS},${tenths % TENTHS} км/ч"
}

/** «32 мин», «1 ч 05 мин» — сколько длилась прогулка. */
fun durationLabel(seconds: Int): String {
    val minutes = seconds / SECONDS_PER_MINUTE
    return if (minutes < MINUTES_PER_HOUR) "$minutes мин" else "${minutes / MINUTES_PER_HOUR} ч ${pad(minutes % MINUTES_PER_HOUR)} мин"
}

/** «32:07», «1:05:00» — таймер идущей прогулки. */
fun timerLabel(seconds: Int): String {
    val h = seconds / SECONDS_PER_HOUR
    val m = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val s = seconds % SECONDS_PER_MINUTE
    return if (h > 0) "$h:${pad(m)}:${pad(s)}" else "$m:${pad(s)}"
}

private fun pad(n: Int) = n.toString().padStart(2, '0')

private const val MINUTES_PER_HOUR = 60
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600
private const val METERS_PER_KM = 1_000
private const val METERS_PER_HUNDREDTH = 10
private const val TENTHS = 10
