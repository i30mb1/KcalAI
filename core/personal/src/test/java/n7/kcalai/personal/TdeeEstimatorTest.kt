package n7.kcalai.personal

import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Идея 6: расход по факту веса и съеденного, а не по формуле. */
class TdeeEstimatorTest {

    /**
     * Человек ест 2000 ккал и теряет 100 г в день.
     *
     * Потеря 0,1 кг в сутки это 770 ккал дефицита, значит расход равен 2770.
     * Формула Миффлина этого человека не знает и ошиблась бы на сотни килокалорий.
     */
    @Test
    fun `расход выводится из дефицита и потери веса`() {
        val weights = (0 until DAYS).associate { day ->
            (TODAY - DAYS + 1 + day) to (80_000 - day * 100)
        }
        val intake = (0 until DAYS).associate { day -> (TODAY - DAYS + 1 + day) to 2000 }

        val estimate = TdeeEstimator.estimate(weights, intake, TODAY)

        assertNotNull(estimate)
        assertTrue(
            "ожидали около 2770, получили ${estimate!!.kcalPerDay}",
            abs(estimate.kcalPerDay - 2770) <= TOLERANCE,
        )
        assertTrue("вес должен падать", estimate.weightTrendGramsPerDay < 0)
    }

    @Test
    fun `при стабильном весе расход равен потреблению`() {
        val weights = (0 until DAYS).associate { day -> (TODAY - DAYS + 1 + day) to 80_000 }
        val intake = (0 until DAYS).associate { day -> (TODAY - DAYS + 1 + day) to 2200 }

        val estimate = TdeeEstimator.estimate(weights, intake, TODAY)!!

        assertTrue(
            "ожидали около 2200, получили ${estimate.kcalPerDay}",
            abs(estimate.kcalPerDay - 2200) <= TOLERANCE,
        )
    }

    /**
     * Главное свойство фильтра: суточный шум воды не должен превращаться в тренд.
     *
     * Вес скачет на ±800 г при неизменной массе — ровно тот случай, из-за которого
     * наивная разность «сегодня минус месяц назад» выдаёт любую цифру, какую попросят.
     */
    @Test
    fun `колебания воды не превращаются в расход`() {
        val weights = (0 until DAYS).associate { day ->
            (TODAY - DAYS + 1 + day) to (80_000 + NOISE[day])
        }
        val intake = (0 until DAYS).associate { day -> (TODAY - DAYS + 1 + day) to 2000 }

        val estimate = TdeeEstimator.estimate(weights, intake, TODAY)!!

        assertTrue(
            "шум дал тренд ${estimate.weightTrendGramsPerDay} г/день",
            abs(estimate.weightTrendGramsPerDay) < 30,
        )
        // Шум не должен уводить и сам расход: он равен съеденному.
        assertTrue(
            "ожидали около 2000, получили ${estimate.kcalPerDay}",
            abs(estimate.kcalPerDay - 2000) <= TOLERANCE,
        )
    }

    /**
     * Фильтр — не украшение: без него задержка воды читается как набор массы.
     *
     * Человек месяц держит вес, а последние четыре дня весы показывают +800 г:
     * соль, углеводы, цикл — что угодно, кроме настоящей массы. Сырой МНК по этим
     * точкам даёт +21 г/день и занижает расход на полторы сотни килокалорий.
     * Калман знает, что настоящая масса меняется не быстрее 100 г/день, а весы
     * врут на 800, и такой хвост почти целиком списывает на измерение.
     */
    @Test
    fun `сглаживание гасит наклон, который дал бы сырой вес`() {
        val days = (0 until DAYS).map { TODAY - DAYS + 1 + it }
        // Ровный вес, и только последние четыре дня — задержка воды.
        val grams = (0 until DAYS).map { if (it >= DAYS - 4) 80_800 else 80_000 }

        val rawSlope = leastSquaresSlope(days.zip(grams.map { it.toDouble() }))
        val estimate = TdeeEstimator.estimate(
            weightsByDay = days.zip(grams).toMap(),
            intakeByDay = days.associateWith { 2000 },
            today = TODAY,
        )!!

        assertTrue(
            "сырой наклон ${"%.1f".format(rawSlope)} г/день должен быть заметным",
            rawSlope > 15,
        )
        assertTrue(
            "фильтр обязан гасить воду: сырой ${"%.1f".format(rawSlope)}, " +
                "сглаженный ${estimate.weightTrendGramsPerDay}",
            estimate.weightTrendGramsPerDay < rawSlope / 2,
        )
    }

    /** Наклон тех же точек без фильтра — то, что было бы при наивном подходе. */
    private fun leastSquaresSlope(points: List<Pair<Long, Double>>): Double {
        val meanDay = points.map { it.first.toDouble() }.average()
        val meanWeight = points.map { it.second }.average()
        var covariance = 0.0
        var variance = 0.0
        for ((day, weight) in points) {
            val dx = day - meanDay
            covariance += dx * (weight - meanWeight)
            variance += dx * dx
        }
        return covariance / variance
    }

    @Test
    fun `по неделе данных оценка не выдаётся`() {
        val weights = (0 until 7).associate { day -> (TODAY - 6 + day) to (80_000 - day * 100) }
        val intake = (0 until 7).associate { day -> (TODAY - 6 + day) to 2000 }

        // Молчать честнее, чем показывать расход, посчитанный по семи точкам.
        assertNull(TdeeEstimator.estimate(weights, intake, TODAY))
    }

    /** Порог ровно на двух неделях: тринадцать точек — молчим, четырнадцать — считаем. */
    @Test
    fun `порог — четырнадцать дней взвешиваний`() {
        fun estimateOver(weighings: Int): TdeeEstimate? {
            val days = (0 until weighings).map { TODAY - weighings + 1 + it }
            return TdeeEstimator.estimate(
                weightsByDay = days.mapIndexed { i, day -> day to 80_000 - i * 100 }.toMap(),
                // Съеденного с запасом: проверяется порог именно по взвешиваниям.
                intakeByDay = (1..DAYS).associate { (TODAY - it) to 2000 },
                today = TODAY,
            )
        }

        assertNull("по тринадцати взвешиваниям оценка — фантазия", estimateOver(13))
        assertNotNull(estimateOver(14))
    }

    private companion object {
        const val DAYS = TdeeEstimator.WINDOW_DAYS

        /** Суточные качели воды при неизменной массе: ±800 г, как на настоящих весах. */
        val NOISE = intArrayOf(
            0, 800, -600, 400, -800, 200, 600, -400, 0, 700, -700, 300, -300, 500,
            -500, 100, -100, 800, -800, 400, -400, 600, -600, 200, -200, 0, 500, -500,
        )

        /**
         * Фильтр на старте отстаёт от линейного тренда и догоняет его за ~8 отсчётов,
         * поэтому наклон по всему окну чуть занижен. Точность в пределах 10% —
         * всё, что вообще имеет смысл обещать при константе 7700 ккал/кг.
         */
        const val TOLERANCE = 280
    }
}
