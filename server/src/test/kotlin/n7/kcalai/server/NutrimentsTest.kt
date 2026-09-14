package n7.kcalai.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutrimentsTest {

    @Test
    fun `обычный продукт правдоподобен`() {
        assertTrue(Nutriments(59, 290, 320, 470).isPlausible())
    }

    @Test
    fun `невозможное отсекается`() {
        assertFalse("отрицательное", Nutriments(59, -1, 0, 0).isPlausible())
        assertFalse("больше чистого жира", Nutriments(901, 0, 10_000, 0).isPlausible())
        assertFalse("макрос больше 100 г", Nutriments(400, 10_001, 0, 0).isPlausible())
        assertFalse("сумма больше 100 г", Nutriments(400, 5_000, 3_000, 3_000).isPlausible())
    }
}
