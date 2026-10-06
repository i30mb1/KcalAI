package n7.kcalai.feature.diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Вес тела из строки ввода.
 *
 * Разбирается до поиска: «82.4 кг» — не блюдо весом 82 килограмма. Границы
 * обязательны — опечатка в весе пишет в историю величину, которую фильтр
 * Калмана расхлёбывает неделю.
 */
class WeightInputTest {

    @Test
    fun `«вес 82,4» и «82,4 кг» — одно и то же взвешивание`() {
        assertEquals(82_400, parseWeight("вес 82,4"))
        assertEquals(82_400, parseWeight("82.4 кг"))
        assertEquals(82_400, parseWeight("  Вес 82.4  "))
    }

    @Test
    fun `целый вес без дробной части тоже принимается`() {
        assertEquals(80_000, parseWeight("вес 80"))
        assertEquals(80_000, parseWeight("80кг"))
    }

    @Test
    fun `еда весом не считается`() {
        assertNull(parseWeight("гречка 200"))
        assertNull(parseWeight("курица 150 г"))
        assertNull(parseWeight("вес"))
        assertNull(parseWeight(""))
    }

    /**
     * Строка либо целиком про вес, либо не про вес.
     *
     * Искать «кг» где-то внутри фразы нельзя: «салат 200 г и кефир 300 мл»
     * не взвешивание, а две позиции, и записать по такой строке 300 кг —
     * значит испортить историю веса на неделю вперёд.
     */
    @Test
    fun `вес узнаётся только по строке целиком`() {
        assertNull(parseWeight("творог 80 кг не бывает"))
        assertNull(parseWeight("купил 82.4 кг картошки"))
        assertNull(parseWeight("вес 82,4 и ещё гречка"))
        // А сама по себе та же строка — честное взвешивание.
        assertEquals(82_400, parseWeight("82,4 кг"))
    }

    @Test
    fun `опечатка за пределами человеческого веса отбрасывается`() {
        assertNull(parseWeight("вес 8"))
        assertNull(parseWeight("вес 824"))
    }
}
