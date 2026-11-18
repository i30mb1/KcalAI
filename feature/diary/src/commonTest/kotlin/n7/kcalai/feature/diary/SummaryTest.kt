package n7.kcalai.feature.diary

import n7.kcalai.model.MealType
import n7.kcalai.personal.DayOutline
import n7.kcalai.personal.TypicalMeal
import n7.kcalai.repositories.DaySummary
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

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

        assertNotNull(summary)
        assertTrue(summary.intro)
        assertTrue(summary.needsGoal)
        assertEquals("Привет! Я Яйцо, слежу за белком", summary.greeting)
    }

    /** Остаток и так виден в шапке: повторять его репликой незачем. */
    @Test
    fun `обычный день с целью — приветствие и ритм дня без калорий цели`() {
        val outline = DayOutline(listOf(TypicalMeal(MealType.BREAKFAST, hour = 8, kcal = 450)))
        val summary = daySummary(goalKcal = 2100, week = week(1800, 2000, 0, goal = 2100), outline = outline, hour = 9)

        assertNotNull(summary)
        assertFalse(summary.intro)
        assertFalse(summary.needsGoal)
        assertEquals("Доброе утро!", summary.greeting)
        assertEquals(outline, summary.outline)
    }

    /** Голое «Доброе утро!» — пустая реплика: сказать персонажу нечего, и он молчит. */
    @Test
    fun `с целью и без ритма дня — сводки нет`() {
        assertNull(daySummary(goalKcal = 2100, week = week(1800, 2000, 0, goal = 2100), outline = null, hour = 9))
    }

    /** Старая фраза просила тапнуть «по остатку», которого без цели на экране нет. */
    @Test
    fun `без цели не новичок — просьба задать цель — не тап по остатку`() {
        val summary = daySummary(goalKcal = null, week = week(1500, 0), outline = null, hour = 14)

        assertNotNull(summary)
        assertFalse(summary.intro)
        assertTrue(summary.needsGoal)
        assertEquals("Добрый день! Цель на сегодня не задана", summary.greeting)
    }

    @Test
    fun `пустой неделе не нужен график неделе с целью — нужен`() {
        assertFalse(hasWeekData(week(0, 0, 0, 0, 0, 0, 0)))
        assertTrue(hasWeekData(week(0, 0, 0, 0, 0, 0, 0, goal = 2000)))
        assertTrue(hasWeekData(week(0, 0, 0, 900, 0, 0, 0)))
    }
}
