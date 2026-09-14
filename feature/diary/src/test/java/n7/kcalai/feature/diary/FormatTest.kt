package n7.kcalai.feature.diary

import n7.kcalai.model.MealType
import n7.kcalai.personal.DayOutline
import n7.kcalai.personal.TypicalMeal
import org.junit.Assert.assertEquals
import org.junit.Test

/** Русские формы в ленте: то, что человек прочтёт, а не то, что посчитано. */
class FormatTest {

    @Test
    fun `сотые грамма показываются с одной десятой`() {
        assertEquals("4,6", formatCentigrams(460))
        assertEquals("12", formatCentigrams(1200))
        assertEquals("0,5", formatCentigrams(55))
    }

    @Test
    fun `граммы после килограмма переходят в килограммы`() {
        assertEquals("200 г", formatGrams(200))
        assertEquals("1 кг", formatGrams(1000))
        assertEquals("1,2 кг", formatGrams(1250))
    }

    @Test
    fun `вес тела всегда с десятыми`() {
        assertEquals("82,4 кг", formatKg(82_450))
        assertEquals("80,0 кг", formatKg(80_000))
    }

    @Test
    fun `счётное слово «день» склоняется по правилу`() {
        assertEquals("день", daysWord(1))
        assertEquals("дня", daysWord(3))
        assertEquals("дней", daysWord(5))
        assertEquals("дней", daysWord(11))
        assertEquals("день", daysWord(21))
        assertEquals("дней", daysWord(0))
    }

    @Test
    fun `сводка называет время один раз`() {
        val outline = DayOutline(
            listOf(
                TypicalMeal(MealType.BREAKFAST, hour = 8, kcal = 450),
                TypicalMeal(MealType.LUNCH, hour = 13, kcal = 850),
                TypicalMeal(MealType.DINNER, hour = 19, kcal = 600),
            )
        )

        assertEquals(
            "Обычно завтрак у вас около 08:00 и это ~450 ккал, обед ~850, ужин ~600",
            outlineSentence(outline),
        )
    }

    @Test
    fun `приветствие по часу`() {
        assertEquals("Доброе утро!", greeting(8))
        assertEquals("Добрый день!", greeting(14))
        assertEquals("Добрый вечер!", greeting(22))
        assertEquals("Добрый вечер!", greeting(3))
    }
}
