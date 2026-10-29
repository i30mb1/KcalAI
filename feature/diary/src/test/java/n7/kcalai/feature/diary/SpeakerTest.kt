package n7.kcalai.feature.diary

import java.time.ZoneOffset
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType
import n7.kcalai.personal.DayPlan
import n7.kcalai.personal.MealGap
import n7.kcalai.ui.mascot.Mascot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Кто из троих говорит реплику.
 *
 * Голос привязан к смыслу, а не к случаю: перебор — всегда Масло, пропущенный
 * приём — всегда Зёрнышко. Иначе персонажи превращаются в случайные картинки
 * у пузырьков, и характера у них не остаётся.
 */
class SpeakerTest {

    @Test
    fun `свои пузырьки и разделитель без персонажа`() {
        assertNull(speakerOf(FeedItem.DaySeparator))
        val meal = groupIntoBubbles(
            listOf(
                DiaryEntryEntity(
                    id = 1, dateEpochDay = 0, meal = MealType.LUNCH, displayName = "Еда",
                    grams = 100, kcal100 = 100, prot100 = 0, fat100 = 0, carb100 = 0,
                    source = EntrySource.TEXT, foodRef = "generic:1", createdAt = 0,
                )
            ),
            ZoneOffset.UTC,
            nowMillis = 0,
        ).single()
        assertNull(speakerOf(meal))
    }

    @Test
    fun `перебор говорит Масло`() {
        assertEquals(Mascot.BUTTER, speakerOf(FeedItem.Over(overKcal = 120)))
    }

    @Test
    fun `пропущенный приём спрашивает Зёрнышко`() {
        val gap = MealGap(MealType.LUNCH, typicalHour = 13, suggestions = emptyList())
        assertEquals(Mascot.GRAIN, speakerOf(FeedItem.Gap(gap)))
    }

    @Test
    fun `остаток с недобранным белком — Яйцо, без него — Зёрнышко`() {
        val withProtein = DayPlan(remainingKcal = 500, remainingProtCg = 2000, options = emptyList())
        val noProtein = DayPlan(remainingKcal = 500, remainingProtCg = 0, options = emptyList())

        assertEquals(Mascot.EGG, speakerOf(FeedItem.Remaining(withProtein, "на ужин")))
        assertEquals(Mascot.GRAIN, speakerOf(FeedItem.Remaining(noProtein, "на ужин")))
    }

    @Test
    fun `остальное говорит Яйцо`() {
        assertEquals(Mascot.EGG, speakerOf(FeedItem.Summary("Доброе утро", weekLine = null, outline = null)))
        assertEquals(Mascot.EGG, speakerOf(FeedItem.NotFound("хрючево")))
        assertEquals(Mascot.EGG, speakerOf(FeedItem.WeightLogged(id = 1, grams = 82_400, time = "08:00")))
        assertEquals(Mascot.EGG, speakerOf(FeedItem.Reply(id = 1, text = "Отправлено", time = "08:00")))
        assertEquals(Mascot.EGG, speakerOf(FeedItem.Outbox(OutboxCount(scans = 1, products = 0), sending = false)))
    }
}
