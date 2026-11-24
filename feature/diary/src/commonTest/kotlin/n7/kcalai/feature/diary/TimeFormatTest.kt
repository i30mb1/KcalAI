package n7.kcalai.feature.diary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone

/** Время записи и час — в местной зоне и с ведущим нулём. */
class TimeFormatTest {

    @Test
    fun `время записи в местной зоне`() {
        // 2025-09-13 00:00 UTC + 7 ч 5 мин.
        val millis = 1_757_721_600_000L + (7 * 60 + 5) * 60_000L

        assertEquals("07:05", formatTime(millis, TimeZone.UTC))
        assertEquals("10:05", formatTime(millis, TimeZone.of("Europe/Moscow")))
    }

    @Test
    fun `час с ведущим нулём`() {
        assertEquals("08:00", formatHour(8))
        assertEquals("13:00", formatHour(13))
    }
}
