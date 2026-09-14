package n7.kcalai.feature.diary

import java.time.ZoneOffset
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType
import n7.kcalai.personal.MealGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Лента дня: какой приём пищи у записи и как записи собираются в пузырьки.
 *
 * Оба правила общие для добавления и для отрисовки, и разойтись им нельзя:
 * подпись «новое добавится сюда» обязана стоять под тем пузырьком, куда запись
 * действительно попадёт.
 */
class FeedTest {

    private val zone = ZoneOffset.UTC

    private fun at(hour: Int, minute: Int = 0): Long = (hour * 60L + minute) * 60_000L

    private fun entry(id: Long, hour: Int, minute: Int = 0, meal: MealType) = DiaryEntryEntity(
        id = id,
        dateEpochDay = 0,
        meal = meal,
        displayName = "Еда $id",
        grams = 100,
        kcal100 = 100,
        prot100 = 0,
        fat100 = 0,
        carb100 = 0,
        source = EntrySource.TEXT,
        foodRef = "generic:$id",
        createdAt = at(hour, minute),
    )

    // --- Приём пищи по часу -------------------------------------------------------------

    @Test
    fun `внутри окон приём определяется расписанием`() {
        assertEquals(MealType.BREAKFAST, mealForHour(8, lastEntry = null, nowMillis = at(8)))
        assertEquals(MealType.LUNCH, mealForHour(13, lastEntry = null, nowMillis = at(13)))
        assertEquals(MealType.DINNER, mealForHour(19, lastEntry = null, nowMillis = at(19)))
    }

    @Test
    fun `в зазоре между окнами продолжается недавняя еда`() {
        // 11:20 — завтрак ещё доедают, если последняя запись была полчаса назад.
        val breakfast = entry(1, hour = 10, minute = 50, meal = MealType.BREAKFAST)

        assertEquals(MealType.BREAKFAST, mealForHour(11, breakfast, nowMillis = at(11, 20)))
    }

    @Test
    fun `в зазоре после часа тишины — перекус`() {
        val breakfast = entry(1, hour = 9, meal = MealType.BREAKFAST)
        val dinner = entry(2, hour = 19, meal = MealType.DINNER)

        assertEquals(MealType.SNACK, mealForHour(11, breakfast, nowMillis = at(11, 30)))
        assertEquals(MealType.SNACK, mealForHour(23, dinner, nowMillis = at(23)))
    }

    @Test
    fun `первая еда дня перекусом не бывает`() {
        // На пустом дневнике зазор отдаётся ближайшему прошедшему приёму.
        assertEquals(MealType.BREAKFAST, mealForHour(11, lastEntry = null, nowMillis = at(11)))
        assertEquals(MealType.LUNCH, mealForHour(16, lastEntry = null, nowMillis = at(16)))
        assertEquals(MealType.DINNER, mealForHour(23, lastEntry = null, nowMillis = at(23)))
        assertEquals(MealType.DINNER, mealForHour(2, lastEntry = null, nowMillis = at(2)))
    }

    // --- Пузырьки ------------------------------------------------------------------------

    @Test
    fun `завтрак — один пузырёк, даже если к нему возвращались`() {
        val bubbles = groupIntoBubbles(
            listOf(
                entry(1, hour = 8, meal = MealType.BREAKFAST),
                entry(2, hour = 10, minute = 30, meal = MealType.BREAKFAST),
            ),
            zone,
            nowMillis = at(13),
        )

        assertEquals(1, bubbles.size)
        assertEquals(2, bubbles.single().entries.size)
        assertEquals("08:00", bubbles.single().time)
        assertEquals(200, bubbles.single().totals.kcal)
    }

    @Test
    fun `перекусы с разрывом больше часа — разные пузырьки`() {
        val bubbles = groupIntoBubbles(
            listOf(
                entry(1, hour = 11, meal = MealType.SNACK),
                entry(2, hour = 11, minute = 40, meal = MealType.SNACK),
                entry(3, hour = 16, meal = MealType.SNACK),
            ),
            zone,
            nowMillis = at(18),
        )

        assertEquals(listOf(2, 1), bubbles.map { it.entries.size })
    }

    @Test
    fun `пузырьки идут по времени суток`() {
        val bubbles = groupIntoBubbles(
            listOf(
                entry(1, hour = 8, meal = MealType.BREAKFAST),
                entry(2, hour = 11, meal = MealType.SNACK),
                entry(3, hour = 13, meal = MealType.LUNCH),
                entry(4, hour = 15, minute = 30, meal = MealType.BREAKFAST),
            ),
            zone,
            nowMillis = at(20),
        )

        assertEquals(listOf(MealType.BREAKFAST, MealType.SNACK, MealType.LUNCH), bubbles.map { it.meal })
    }

    @Test
    fun `перенесённая в завтрак запись встаёт перед обедом`() {
        // Человек в 13:00 записал обед, потом поправил: это был завтрак.
        // Время у записи осталось обеденное, место в ленте — уже нет.
        val bubbles = groupIntoBubbles(
            listOf(
                entry(1, hour = 12, minute = 30, meal = MealType.LUNCH),
                entry(2, hour = 13, meal = MealType.BREAKFAST),
            ),
            zone,
            nowMillis = at(14),
        )

        assertEquals(listOf(MealType.BREAKFAST, MealType.LUNCH), bubbles.map { it.meal })
    }

    @Test
    fun `поздний ужин и перекус между приёмами остаются на своих местах`() {
        val bubbles = groupIntoBubbles(
            listOf(
                entry(1, hour = 8, meal = MealType.BREAKFAST),
                entry(2, hour = 11, meal = MealType.SNACK),
                entry(3, hour = 13, meal = MealType.LUNCH),
                entry(4, hour = 23, minute = 30, meal = MealType.DINNER),
            ),
            zone,
            nowMillis = at(23, 45),
        )

        assertEquals(
            listOf(MealType.BREAKFAST, MealType.SNACK, MealType.LUNCH, MealType.DINNER),
            bubbles.map { it.meal },
        )
    }

    @Test
    fun `активен пузырёк, куда упадёт следующая запись`() {
        val bubbles = groupIntoBubbles(
            listOf(
                entry(1, hour = 8, meal = MealType.BREAKFAST),
                entry(2, hour = 13, meal = MealType.LUNCH),
            ),
            zone,
            nowMillis = at(14),
        )

        assertFalse(bubbles[0].active)
        assertTrue("в 14:00 новое добавится в обед", bubbles[1].active)
    }

    @Test
    fun `остывший перекус активным не бывает`() {
        val bubbles = groupIntoBubbles(
            listOf(entry(1, hour = 11, meal = MealType.SNACK)),
            zone,
            nowMillis = at(16, 30),
        )

        // В 16:30 правило даёт перекус, но последний кластер уже остыл —
        // запись создаст новый пузырёк, и указывать на старый было бы ложью.
        assertFalse(bubbles.single().active)
    }

    // --- Вопрос про пропуск ---------------------------------------------------------------

    @Test
    fun `вопрос про обед встаёт между завтраком и ужином`() {
        val bubbles = groupIntoBubbles(
            listOf(
                entry(1, hour = 8, meal = MealType.BREAKFAST),
                entry(2, hour = 19, meal = MealType.DINNER),
            ),
            zone,
            nowMillis = at(20),
        )
        val gap = MealGap(MealType.LUNCH, typicalHour = 13, suggestions = emptyList())

        assertEquals(1, gapPosition(bubbles, gap, zone))
    }

    @Test
    fun `без записей до типичного часа вопрос стоит первым`() {
        val bubbles = groupIntoBubbles(
            listOf(entry(1, hour = 19, meal = MealType.DINNER)),
            zone,
            nowMillis = at(20),
        )
        val gap = MealGap(MealType.BREAKFAST, typicalHour = 8, suggestions = emptyList())

        assertEquals(0, gapPosition(bubbles, gap, zone))
    }
}
