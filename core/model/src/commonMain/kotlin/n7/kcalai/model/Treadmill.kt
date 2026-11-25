package n7.kcalai.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Что сообщает беговая дорожка в пакете FTMS: счётчики — с момента её запуска. */
data class TreadmillSample(val speedKmh: Double, val distanceM: Int, val kcal: Int, val elapsedS: Int)

/** Одна прогулка по дорожке. [startedAt] — её имя: по нему прогулка узнаётся после переподключения. */
data class Walk(
    val startedAt: Long,
    val seenAt: Long,
    val distanceM: Int,
    val kcal: Int,
    val elapsedS: Int,
    val steps: Double,
    val topSpeedKmh: Double,
) {
    /** Средний темп, км/ч. */
    val avgSpeedKmh: Double get() = if (elapsedS > 0) distanceM / elapsedS.toDouble() * MPS_TO_KMH else 0.0

    /** Дорожка давно не шлёт новых метров — прогулка кончилась, её итог можно рассказать. */
    fun isOver(now: Long): Boolean = now - seenAt > WALK_OVER_MS
}

data class WalkTotals(val steps: Int, val distanceM: Int, val elapsedS: Int, val kcal: Int, val walks: Int)

/** Сервис фитнес-тренажёров (FTMS) и его характеристика «данные дорожки» — по ним страница ищет дорожку. */
const val FTMS_SERVICE = 0x1826
const val TREADMILL_DATA = 0x2ACD

/** Столько дорожка может молчать или стоять, пока прогулка ещё не кончилась: отошли попить — не новая. */
const val WALK_OVER_MS = 3 * 60_000L

/**
 * Пакет Treadmill Data (0x2ACD): флаги, за ними — только поля, чей бит взведён. Бит 0 наоборот:
 * ноль значит «скорость есть». Umay-TM взводит ещё бит 13, которого нет в стандарте, — он не мешает.
 * Обрезанный пакет — null.
 */
@Suppress("MagicNumber", "CyclomaticComplexMethod")
fun parseTreadmillData(bytes: ByteArray): TreadmillSample? {
    var i = 0
    fun u8(): Int = (bytes[i++].toInt() and 0xFF)
    fun u16(): Int = u8() or (u8() shl 8)
    fun u24(): Int = u16() or (u8() shl 16)
    return runCatching {
        val flags = u16()
        fun has(bit: Int) = flags and (1 shl bit) != 0
        var speed = 0.0
        var distance = 0
        var kcal = 0
        var elapsed = 0
        if (!has(0)) speed = u16() / 100.0
        if (has(1)) u16()
        if (has(2)) distance = u24()
        if (has(3)) { u16(); u16() }
        if (has(4)) { u16(); u16() }
        if (has(5)) u8()
        if (has(6)) u8()
        if (has(7)) { kcal = u16(); u16(); u8() }
        if (has(8)) u8()
        if (has(9)) u8()
        if (has(10)) elapsed = u16()
        TreadmillSample(speed, distance, kcal, elapsed)
    }.getOrNull()
}

/** Длина шага, м: растёт с ростом и со скоростью. На 2 км/ч у человека 175 см — около 58 см. */
@Suppress("MagicNumber")
fun strideMeters(speedKmh: Double, heightCm: Int = DEFAULT_HEIGHT_CM): Double =
    heightCm / 100.0 * (0.26 + 0.035 * speedKmh.coerceIn(1.0, 12.0))

/**
 * Пакет дорожки → прогулка: продолжение [last] или новая. Дорожка шлёт счётчики с момента запуска,
 * поэтому прогулка хранит их последние значения, а не прибавки — повтор пакета и переподключение
 * ничего не удваивают. Дорожка включена, но не запущена — прогулки нет (null).
 */
fun track(last: Walk?, sample: TreadmillSample, now: Long, heightCm: Int = DEFAULT_HEIGHT_CM): Walk? {
    if (sample.distanceM == 0 && sample.elapsedS == 0) return null
    val startedAt = now - sample.elapsedS * MS_PER_S
    val continues = last != null &&
        sample.distanceM >= last.distanceM &&
        sample.elapsedS >= last.elapsedS &&
        (now - last.seenAt <= SHORT_GAP_MS || abs(startedAt - last.startedAt) <= SAME_START_MS)
    if (last != null && continues) {
        val pace = if (sample.speedKmh > 0) sample.speedKmh else avgKmh(sample)
        return last.copy(
            seenAt = now,
            distanceM = sample.distanceM,
            kcal = sample.kcal,
            elapsedS = sample.elapsedS,
            steps = last.steps + (sample.distanceM - last.distanceM) / strideMeters(pace, heightCm),
            topSpeedKmh = max(last.topSpeedKmh, sample.speedKmh),
        )
    }
    return Walk(
        startedAt = startedAt,
        seenAt = now,
        distanceM = sample.distanceM,
        kcal = sample.kcal,
        elapsedS = sample.elapsedS,
        steps = sample.distanceM / strideMeters(avgKmh(sample), heightCm),
        topSpeedKmh = sample.speedKmh,
    )
}

/** Итог прогулок — дня или любой другой выборки. */
fun List<Walk>.totals(): WalkTotals = WalkTotals(
    steps = sumOf { it.steps }.roundToInt(),
    distanceM = sumOf { it.distanceM },
    elapsedS = sumOf { it.elapsedS },
    kcal = sumOf { it.kcal },
    walks = size,
)

private fun avgKmh(s: TreadmillSample) = if (s.elapsedS > 0) s.distanceM / s.elapsedS.toDouble() * MPS_TO_KMH else 0.0

/** «6 240» — шаги до единицы, с разрядами: в ленте телефона и на странице одинаково. */
fun stepsLabel(steps: Int): String = steps.toString().reversed().chunked(DIGITS_GROUP).joinToString(" ").reversed()

private const val DIGITS_GROUP = 3

/** Рост, пока в профиле его нет: длина шага считается по нему. */
const val DEFAULT_HEIGHT_CM = 175

/** Цель шагов на день, пока её не задали. */
const val DEFAULT_STEPS_GOAL = 8_000

/**
 * Цель шагов и рост — из профиля телефона.
 *
 * @param heightCm рост; `null` — не называли, шаг меряется по [DEFAULT_HEIGHT_CM]
 */
data class StepsSettings(val goal: Int = DEFAULT_STEPS_GOAL, val heightCm: Int? = null)

/** Цель шагов, которую можно задать: от тысячи до пятидесяти тысяч. */
val STEPS_GOAL_RANGE = 1_000..50_000

/** Рост, по которому считают: и шаг дорожки, и помощник цели. */
val HEIGHT_CM_RANGE = 120..230

/** Цель шагов из набранного; вне [STEPS_GOAL_RANGE] — не цель. */
fun stepsGoalInput(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in STEPS_GOAL_RANGE }

/** Рост из набранного, см; вне [HEIGHT_CM_RANGE] — не рост. */
fun heightInput(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in HEIGHT_CM_RANGE }

/** Прогулка, к которой вернулись, узнаётся по началу: «сейчас минус время дорожки». */
private const val SAME_START_MS = 90_000L

/** Короткий обрыв связи — та же прогулка, даже если таймер дорожки стоял на паузе. */
private const val SHORT_GAP_MS = 180_000L
private const val MS_PER_S = 1_000L
private const val MPS_TO_KMH = 3.6
