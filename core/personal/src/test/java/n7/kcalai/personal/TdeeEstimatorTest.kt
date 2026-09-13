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
        val noise = intArrayOf(0, 800, -600, 400, -800, 200, 600, -400, 0, 700, -700, 300, -300, 500,
            -500, 100, -100, 800, -800, 400, -400, 600, -600, 200, -200, 0, 500, -500)
        val weights = (0 until DAYS).associate { day ->
            (TODAY - DAYS + 1 + day) to (80_000 + noise[day])
        }
        val intake = (0 until DAYS).associate { day -> (TODAY - DAYS + 1 + day) to 2000 }

        val estimate = TdeeEstimator.estimate(weights, intake, TODAY)!!

        assertTrue(
            "шум дал тренд ${estimate.weightTrendGramsPerDay} г/день",
            abs(estimate.weightTrendGramsPerDay) < 30,
        )
    }

    @Test
    fun `по неделе данных оценка не выдаётся`() {
        val weights = (0 until 7).associate { day -> (TODAY - 6 + day) to (80_000 - day * 100) }
        val intake = (0 until 7).associate { day -> (TODAY - 6 + day) to 2000 }

        // Молчать честнее, чем показывать расход, посчитанный по семи точкам.
        assertNull(TdeeEstimator.estimate(weights, intake, TODAY))
    }

    private companion object {
        const val DAYS = TdeeEstimator.WINDOW_DAYS

        /**
         * Фильтр на старте отстаёт от линейного тренда и догоняет его за ~8 отсчётов,
         * поэтому наклон по всему окну чуть занижен. Точность в пределах 10% —
         * всё, что вообще имеет смысл обещать при константе 7700 ккал/кг.
         */
        const val TOLERANCE = 280
    }
}
