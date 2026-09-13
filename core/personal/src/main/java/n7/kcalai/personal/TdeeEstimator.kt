package n7.kcalai.personal

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Оценка суточного расхода по факту, а не по формуле.
 *
 * @param kcalPerDay сам расход
 * @param marginKcal половина доверительного интервала — «± столько»
 * @param days сколько дней окна попало в расчёт
 * @param weightTrendGramsPerDay куда и с какой скоростью едет вес
 */
data class TdeeEstimate(
    val kcalPerDay: Int,
    val marginKcal: Int,
    val days: Int,
    val weightTrendGramsPerDay: Int,
)

/**
 * Идея 6: обратная задача дневника.
 *
 * Формулы вроде Миффлина-Сан Жеора считают расход по росту, весу и возрасту и ошибаются
 * на ±20% — потому что метаболизм человека ими не определяется. Но у приложения есть
 * то, чего нет у формулы: сколько человек реально съел и что реально произошло с весом.
 * Из этой пары расход выводится напрямую.
 *
 * Мешает одно — суточный вес шумит на ±1 кг из-за воды, гликогена и содержимого
 * кишечника, и это шум того же порядка, что и месячный полезный сигнал. Отсюда
 * фильтр Калмана: он разделяет «настоящую массу, которая меняется медленно»
 * и «сегодняшнее показание весов, которое врёт».
 */
object TdeeEstimator {

    /**
     * Энергоёмкость массы тела, ккал на килограмм.
     *
     * Классические 7700 — это чистый жир. Реальное изменение массы всегда смесь
     * жира, гликогена с водой и немного мышц, поэтому цифра приблизительна
     * по своей природе, и уточнять её нечем.
     */
    private const val KCAL_PER_KG = 7700.0

    /** Окно расчёта. Меньше месяца — и шум воды перекрывает тренд. */
    const val WINDOW_DAYS = 28

    /**
     * Шум процесса: насколько настоящая масса может измениться за сутки.
     * 100 г/день — это 3 кг в месяц, верхняя граница осмысленного темпа.
     */
    private const val PROCESS_NOISE_G = 100.0

    /**
     * Шум измерения: суточные колебания веса, не связанные с массой тела.
     * 800 г — консервативная оценка для утреннего взвешивания.
     */
    private const val MEASUREMENT_NOISE_G = 800.0

    /**
     * Ниже этого числа точек оценка — фантазия. Молчать честнее.
     *
     * Публичная, потому что UI обязан объяснить человеку, чего именно не хватает:
     * пустое место на экране читается как поломка, а «нужно 14 дней» — как ожидание.
     */
    const val MIN_WEIGHT_DAYS = 14
    private const val MIN_INTAKE_DAYS = 14

    /**
     * @param weightsByDay вес в граммах по дням; пропуски допустимы и ожидаемы
     * @param intakeByDay съеденные калории по дням; дни без записей передавать НЕ нужно
     * @return `null`, если данных не хватает — это нормальное состояние первых недель
     */
    fun estimate(
        weightsByDay: Map<Long, Int>,
        intakeByDay: Map<Long, Int>,
        today: Long,
    ): TdeeEstimate? {
        val from = today - WINDOW_DAYS + 1
        val weights = weightsByDay.filterKeys { it in from..today }
        val intake = intakeByDay.filterKeys { it in from..today }

        if (weights.size < MIN_WEIGHT_DAYS || intake.size < MIN_INTAKE_DAYS) return null

        val smoothed = smooth(weights, from, today)
        val trend = trend(smoothed) ?: return null

        // Вес падает -> расход был больше съеденного ровно на энергию потерянной массы.
        val meanIntake = intake.values.average()
        val kcalPerDay = meanIntake - trend.slopeGramsPerDay / 1000.0 * KCAL_PER_KG

        return TdeeEstimate(
            kcalPerDay = kcalPerDay.roundToInt(),
            marginKcal = (trend.slopeStdError / 1000.0 * KCAL_PER_KG * CONFIDENCE_Z).roundToInt(),
            days = weights.size,
            weightTrendGramsPerDay = trend.slopeGramsPerDay.roundToInt(),
        )
    }

    /**
     * Одномерный фильтр Калмана по весу.
     *
     * В дни без взвешивания выполняется только предсказание: состояние переносится
     * вперёд, неопределённость растёт. В дни с измерением к нему добавляется
     * коррекция с весом, зависящим от того, чему сейчас доверия больше —
     * накопленной оценке или новому показанию весов.
     */
    private fun smooth(weights: Map<Long, Int>, from: Long, to: Long): List<Pair<Long, Double>> {
        val q = PROCESS_NOISE_G * PROCESS_NOISE_G
        val r = MEASUREMENT_NOISE_G * MEASUREMENT_NOISE_G

        var state = weights[weights.keys.min()]?.toDouble() ?: return emptyList()
        var variance = r
        val result = mutableListOf<Pair<Long, Double>>()

        for (day in from..to) {
            // Предсказание: масса инерционна, поэтому переносим как есть.
            variance += q

            val measurement = weights[day]
            if (measurement != null) {
                val gain = variance / (variance + r)
                state += gain * (measurement - state)
                variance *= (1 - gain)
                result += day to state
            }
        }
        return result
    }

    private class Trend(val slopeGramsPerDay: Double, val slopeStdError: Double)

    /** Наклон сглаженного веса методом наименьших квадратов плюс ошибка наклона. */
    private fun trend(points: List<Pair<Long, Double>>): Trend? {
        if (points.size < MIN_WEIGHT_DAYS) return null

        val meanDay = points.map { it.first.toDouble() }.average()
        val meanWeight = points.map { it.second }.average()

        var covariance = 0.0
        var variance = 0.0
        for ((day, weight) in points) {
            val dx = day - meanDay
            covariance += dx * (weight - meanWeight)
            variance += dx * dx
        }
        if (variance == 0.0) return null

        val slope = covariance / variance

        var residualSumSquares = 0.0
        for ((day, weight) in points) {
            val predicted = meanWeight + slope * (day - meanDay)
            residualSumSquares += (weight - predicted) * (weight - predicted)
        }
        val degreesOfFreedom = points.size - 2
        val standardError = if (degreesOfFreedom <= 0) {
            abs(slope)
        } else {
            sqrt(residualSumSquares / degreesOfFreedom / variance)
        }

        return Trend(slopeGramsPerDay = slope, slopeStdError = standardError)
    }

    /** ~95% для нормального приближения. Интервал и так грубый, точность z здесь лишняя. */
    private const val CONFIDENCE_Z = 1.96
}
