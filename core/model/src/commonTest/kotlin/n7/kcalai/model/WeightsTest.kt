package n7.kcalai.model

import kotlin.test.Test
import kotlin.test.assertEquals

class WeightsTest {

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
}
