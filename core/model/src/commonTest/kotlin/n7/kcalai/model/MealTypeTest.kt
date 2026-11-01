package n7.kcalai.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Названия приёмов — одни на ленту и на реплики персонажей. */
class MealTypeTest {

    @Test
    fun `названия приёмов пищи`() {
        assertEquals(listOf("Завтрак", "Обед", "Ужин", "Перекус"), MealType.entries.map { it.title })
    }

    @Test
    fun `родительный — «сегодня обеда в дневнике нет»`() {
        assertEquals(listOf("завтрака", "обеда", "ужина", "перекуса"), MealType.entries.map { it.genitive })
    }
}
