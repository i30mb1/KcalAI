package n7.kcalai.feature.diary

import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Как лента бережёт место, как в мессенджерах: время встаёт в строку с концом
 * реплики, а реплики одной стороны подряд стоят тесно.
 */
class BubbleLayoutTest {

    // --- Время в пузырьке -----------------------------------------------------------------

    @Test
    fun `короткая реплика — время в той же строке, пузырёк чуть шире текста`() {
        val slot = timeSlot(textWidth = 200, lastLineRight = 200, timeWidth = 60, gapPx = 16, maxWidth = 600)

        assertEquals(TimeSlot(inline = true, width = 276), slot)
    }

    @Test
    fun `последняя строка короткая — время рядом с ней, ширина пузырька та же`() {
        val slot = timeSlot(textWidth = 580, lastLineRight = 150, timeWidth = 60, gapPx = 16, maxWidth = 600)

        assertEquals(TimeSlot(inline = true, width = 580), slot)
    }

    @Test
    fun `последняя строка во всю ширину — время уходит строкой ниже`() {
        val slot = timeSlot(textWidth = 590, lastLineRight = 590, timeWidth = 60, gapPx = 16, maxWidth = 600)

        assertEquals(TimeSlot(inline = false, width = 590), slot)
    }

    @Test
    fun `влезает впритык — ещё в строку`() {
        val slot = timeSlot(textWidth = 524, lastLineRight = 524, timeWidth = 60, gapPx = 16, maxWidth = 600)

        assertTrue(slot.inline)
    }

    // --- Зазоры между репликами -----------------------------------------------------------

    @Test
    fun `реплики одной стороны подряд — тесно, смена стороны — просторнее`() {
        val reply = FeedItem.Reply(id = 1, text = "Отправлено", time = "09:00")
        val weight = FeedItem.WeightLogged(id = 2, grams = 82_400, time = "09:01")

        assertEquals(SAME_SIDE_GAP, gapBefore(reply, weight))
        assertEquals(SAME_SIDE_GAP, gapBefore(meal(), meal()))
        assertEquals(SIDE_CHANGE_GAP, gapBefore(meal(), reply))
        assertEquals(SIDE_CHANGE_GAP, gapBefore(reply, meal()))
        assertTrue(SAME_SIDE_GAP < SIDE_CHANGE_GAP)
    }

    @Test
    fun `над первым элементом зазора нет, после разделителя дня — как при смене стороны`() {
        val separator = FeedItem.DaySeparator("сегодня")

        assertEquals(0.dp, gapBefore(null, separator))
        assertEquals(SIDE_CHANGE_GAP, gapBefore(separator, meal()))
        assertEquals(SIDE_CHANGE_GAP, gapBefore(separator, FeedItem.Reply(id = 1, text = "Привет", time = "09:00")))
    }

    private fun meal(): FeedItem = groupIntoBubbles(
        listOf(
            DiaryEntryEntity(
                id = 1, dateEpochDay = 0, meal = MealType.LUNCH, displayName = "Еда",
                grams = 100, kcal100 = 100, prot100 = 0, fat100 = 0, carb100 = 0,
                source = EntrySource.TEXT, foodRef = "generic:1", createdAt = 0,
            )
        ),
        TimeZone.UTC,
        nowMillis = 0,
    ).single()
}
