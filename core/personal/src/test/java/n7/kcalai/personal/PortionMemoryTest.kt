package n7.kcalai.personal

import kotlinx.coroutines.runBlocking
import n7.kcalai.model.FoodRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Идея 3: сколько грамм у ЭТОГО человека весит порция. */
class PortionMemoryTest {

    private val dao = FakePersonalDao()
    private val memory = PortionMemory(dao)
    private val soup = FoodRef.Generic(42)

    @Test
    fun `двух подтверждений мало — табличная порция остаётся`() = runBlocking {
        memory.remember(soup, PortionMemory.PORTION_UNIT, 400, now = 1)
        memory.remember(soup, PortionMemory.PORTION_UNIT, 400, now = 2)

        assertNull(
            "после двух наблюдений личная порция ещё не достовернее средней по миру",
            memory.unitsFor(soup)[PortionMemory.PORTION_UNIT],
        )
    }

    @Test
    fun `с третьего подтверждения берётся личная медиана`() = runBlocking {
        memory.remember(soup, PortionMemory.PORTION_UNIT, 350, now = 1)
        memory.remember(soup, PortionMemory.PORTION_UNIT, 400, now = 2)
        memory.remember(soup, PortionMemory.PORTION_UNIT, 450, now = 3)

        assertEquals(400, memory.unitsFor(soup)[PortionMemory.PORTION_UNIT])
    }

    @Test
    fun `разовый промах по клавиатуре медиану не сдвигает`() = runBlocking {
        memory.remember(soup, PortionMemory.PORTION_UNIT, 400, now = 1)
        memory.remember(soup, PortionMemory.PORTION_UNIT, 400, now = 2)
        memory.remember(soup, PortionMemory.PORTION_UNIT, 2500, now = 3)

        // Среднее дало бы 1100 г супа. Медиана — то, ради чего она здесь и стоит.
        assertEquals(400, memory.unitsFor(soup)[PortionMemory.PORTION_UNIT])
    }

    @Test
    fun `единицы одного продукта не смешиваются`() = runBlocking {
        repeat(3) { memory.remember(soup, PortionMemory.PORTION_UNIT, 400, now = it.toLong()) }
        repeat(3) { memory.remember(soup, "ст.л.", 15, now = 10L + it) }

        val units = memory.unitsFor(soup)
        assertEquals(400, units[PortionMemory.PORTION_UNIT])
        assertEquals(15, units["ст.л."])
    }

    @Test
    fun `абсурдный вес в статистику не попадает`() = runBlocking {
        repeat(3) { memory.remember(soup, PortionMemory.PORTION_UNIT, 999_999, now = it.toLong()) }

        assertNull(memory.unitsFor(soup)[PortionMemory.PORTION_UNIT])
    }
}
