package n7.kcalai.webapi

import kotlin.test.Test
import kotlin.test.assertEquals

/** Подписи прогулок на странице — как на экране дорожки: разряды, десятые, минуты. */
class WalkLabelsTest {

    @Test
    fun `шаги — с разрядами`() {
        assertEquals("640", stepsLabel(640))
        assertEquals("6 240", stepsLabel(6_240))
        assertEquals("12 000", stepsLabel(12_000))
    }

    @Test
    fun `время дня из минут`() {
        assertEquals("08:05", clockLabel(8 * 60 + 5))
        assertEquals("24:00", clockLabel(24 * 60))
    }

    @Test
    fun `путь — километры с сотыми, темп — с десятой`() {
        assertEquals("2,14 км", kmLabel(2_140))
        assertEquals("0,05 км", kmLabel(50))
        assertEquals("4,0 км/ч", speedLabel(4.0))
        assertEquals("3,5 км/ч", speedLabel(3.46))
    }

    @Test
    fun `сколько шли — минуты, после часа — часы и минуты, на ходу — секунды`() {
        assertEquals("32 мин", durationLabel(32 * 60 + 40))
        assertEquals("1 ч 05 мин", durationLabel(65 * 60))
        assertEquals("32:07", timerLabel(32 * 60 + 7))
        assertEquals("1:05:00", timerLabel(65 * 60))
    }
}
