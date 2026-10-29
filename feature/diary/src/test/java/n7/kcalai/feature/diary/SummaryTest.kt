package n7.kcalai.feature.diary

import n7.kcalai.repositories.DaySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сводка пустого дня и график недели над ней.
 *
 * Знакомство с персонажами — для того, у кого за неделю нет ни одной записи:
 * каждое утро представляться заново было бы навязчиво, а молчать перед новичком —
 * оставить его с пустым экраном и непонятными картинками у реплик.
 */
class SummaryTest {

    private fun week(vararg kcal: Int, goal: Int? = null) =
        kcal.mapIndexed { i, k -> DaySummary(dateEpochDay = i.toLong(), kcal = k, goalKcal = goal) }

    @Test
    fun `новичок без цели — знакомство и кнопка цели`() {
        val summary = daySummary(goalKcal = null, week = week(0, 0, 0, 0, 0, 0, 0), outline = null, hour = 9)

        assertTrue(summary.intro)
        assertTrue(summary.needsGoal)
        assertEquals("Привет! Я Яйцо, слежу за белком", summary.greeting)
    }

    @Test
    fun `обычный день с целью — приветствие и сколько можно`() {
        val summary = daySummary(goalKcal = 2100, week = week(1800, 2000, 0, goal = 2100), outline = null, hour = 9)

        assertFalse(summary.intro)
        assertFalse(summary.needsGoal)
        assertEquals("Доброе утро! Сегодня можно 2100 ккал", summary.greeting)
    }

    /** Старая фраза просила тапнуть «по остатку», которого без цели на экране нет. */
    @Test
    fun `без цели не новичок — просьба задать цель, а не тап по остатку`() {
        val summary = daySummary(goalKcal = null, week = week(1500, 0), outline = null, hour = 14)

        assertFalse(summary.intro)
        assertTrue(summary.needsGoal)
        assertEquals("Добрый день! Цель на сегодня не задана", summary.greeting)
    }

    @Test
    fun `неделя считается только по дням с целью`() {
        val days = listOf(
            DaySummary(0, kcal = 1500, goalKcal = null),
            DaySummary(1, kcal = 1900, goalKcal = 2000),
            DaySummary(2, kcal = 2300, goalKcal = 2000),
        )
        val summary = daySummary(goalKcal = 2000, week = days, outline = null, hour = 9)

        assertEquals("За неделю вы уложились в цель 1 день из 2", summary.weekLine)
        assertNull(daySummary(goalKcal = null, week = week(0, 0), outline = null, hour = 9).weekLine)
    }

    @Test
    fun `пустой неделе не нужен график, неделе с целью — нужен`() {
        assertFalse(hasWeekData(week(0, 0, 0, 0, 0, 0, 0)))
        assertTrue(hasWeekData(week(0, 0, 0, 0, 0, 0, 0, goal = 2000)))
        assertTrue(hasWeekData(week(0, 0, 0, 900, 0, 0, 0)))
    }
}
