package n7.kcalai.feature.diary

import n7.kcalai.model.FiberTotal
import n7.kcalai.model.MealType
import n7.kcalai.personal.DayOutline
import n7.kcalai.personal.TypicalMeal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/** Русские формы в ленте: то, что человек прочтёт, а не то, что посчитано. */
class FormatTest {

    @Test
    fun `сотые грамма показываются с одной десятой`() {
        assertEquals("4,6", formatCentigrams(460))
        assertEquals("12", formatCentigrams(1200))
        assertEquals("0,5", formatCentigrams(55))
    }

    @Test
    fun `клетчатка целыми граммами неполная — «не меньше» неизвестная — нигде`() {
        assertEquals("8", formatFiber(FiberTotal(cg = 810, unknown = 0)))
        assertEquals("≥8", formatFiber(FiberTotal(cg = 810, unknown = 1)))
        assertEquals("0", formatFiber(FiberTotal(cg = 0, unknown = 0)))
        assertEquals(null, formatFiber(FiberTotal(cg = 0, unknown = 2)))
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

    /**
     * Род запроса не угадать: «„Гречка“ не нашлась» верно, «„Борщ“ не нашлась»
     * и «„Яйца“ не нашлась» — нет. Фраза строится так, чтобы род был не нужен.
     */
    @Test
    fun `реплика «не нашлось» не зависит от рода запроса`() {
        listOf("борщ", "гречка", "яйца", "молоко").forEach { query ->
            val text = notFoundText(query)
            assertTrue(text.startsWith("По запросу «$query» ничего не нашлось."), text)
            assertTrue("штрих-код" in text && "этикетку" in text, text)
            // Персонажи говорят на «ты»: «Попробуйте» от Яйца звучало бы чужим голосом.
            assertTrue("Попробуй назвать" in text, text)
        }
    }

    /** Подпись над перепиской дня: сегодня и вчера — словом, дальше — датой. */
    @Test
    fun `день ленты называется словом пока это возможно`() {
        val today = kotlinx.datetime.LocalDate(2026, 10, 31)

        assertEquals("сегодня", dayLabel(today, today))
        assertEquals("вчера", dayLabel(kotlinx.datetime.LocalDate(2026, 10, 30), today))
        assertEquals("четверг, 29 окт", dayLabel(kotlinx.datetime.LocalDate(2026, 10, 29), today))
    }

    @Test
    fun `счётное слово в очереди отправки склоняется по правилу`() {
        assertEquals("1 продукт", describeOutbox(scans = 0, products = 1))
        assertEquals("3 продукта", describeOutbox(scans = 0, products = 3))
        assertEquals("5 продуктов", describeOutbox(scans = 0, products = 5))
        assertEquals("11 продуктов", describeOutbox(scans = 0, products = 11))
        assertEquals("21 продукт", describeOutbox(scans = 0, products = 21))
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
            "Обычно завтрак у тебя около 08:00 и это ~450 ккал, обед ~850, ужин ~600",
            outlineSentence(outline),
        )
    }

    @Test
    fun `очередь отправки описывается по-русски`() {
        assertEquals("1 сессия съёмки", describeOutbox(1, 0))
        assertEquals("2 сессии съёмки и 1 продукт", describeOutbox(2, 1))
        assertEquals("5 сессий съёмки и 12 продуктов", describeOutbox(5, 12))
        assertEquals("3 продукта", describeOutbox(0, 3))
    }

    @Test
    fun `приветствие по часу`() {
        assertEquals("Доброе утро!", greeting(8))
        assertEquals("Добрый день!", greeting(14))
        assertEquals("Добрый вечер!", greeting(22))
        assertEquals("Добрый вечер!", greeting(3))
    }

    /** Границы суток названы точно: сдвиг на час читается как сломанные часы. */
    @Test
    fun `границы приветствий стоят на своих часах`() {
        assertEquals("Добрый вечер!", greeting(4))
        assertEquals("Доброе утро!", greeting(5))
        assertEquals("Доброе утро!", greeting(11))
        assertEquals("Добрый день!", greeting(12))
        assertEquals("Добрый день!", greeting(17))
        assertEquals("Добрый вечер!", greeting(18))
    }
}
