package n7.kcalai.feature.diary

import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.parseFoodRef
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import n7.kcalai.personal.MealGap
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.ui.mascot.Mascot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Где стоит каждый из троих.
 *
 * Персонаж один на экран: у последней своей реплики, на чипсе, когда речь о его
 * макросе, или дома в плитке. Порядок важности именно такой — подсказка под
 * пальцем важнее того, что было сказано раньше.
 */
class MascotPlacementTest {

    private val intro = FeedItem.Summary("Привет! Я Яйцо, слежу за белком", weekLine = null, outline = null, intro = true)
    private val greeting = FeedItem.Summary("Доброе утро!", weekLine = null, outline = null)
    private val over = FeedItem.Over(overKcal = 100)
    private val gap = FeedItem.Gap(MealGap(MealType.LUNCH, typicalHour = 13, suggestions = emptyList()))

    private fun candidate(prot: Int, fat: Int, carb: Int) = FoodCandidate(
        ref = parseFoodRef("generic:1")!!,
        displayName = "еда",
        nutriments = Nutriments(kcal100 = 100, prot100 = prot, fat100 = fat, carb100 = carb),
        servingG = 100,
    )

    @Test
    fun `в первый день все трое выходят на сцену`() {
        val places = mascotPlaces(listOf(FeedItem.DaySeparator, intro), shownKeys = setOf(intro.key), chipOwner = null)

        Mascot.entries.forEach { assertEquals(MascotPlace.Stage, places[it]) }
    }

    @Test
    fun `чипс важнее реплики и сцены`() {
        val places = mascotPlaces(listOf(intro, over), shownKeys = setOf(intro.key, over.key), chipOwner = Mascot.BUTTER)

        assertEquals(MascotPlace.Chip, places[Mascot.BUTTER])
        // Сцена кончилась, как только человек начал печатать: остальные — по своим местам.
        assertEquals(MascotPlace.Reply(intro.key), places[Mascot.EGG])
        assertEquals(MascotPlace.Home, places[Mascot.GRAIN])
    }

    @Test
    fun `у последней видимой реплики, а не у первой`() {
        val older = FeedItem.Reply(id = 1, text = "раньше", time = "08:00")
        val newer = FeedItem.Reply(id = 2, text = "сейчас", time = "09:00")
        val places = mascotPlaces(listOf(greeting, older, newer, gap), shownKeys = setOf(greeting.key, older.key, newer.key, gap.key), chipOwner = null)

        assertEquals(MascotPlace.Reply(newer.key), places[Mascot.EGG])
        assertEquals(MascotPlace.Reply(gap.key), places[Mascot.GRAIN])
        assertEquals(MascotPlace.Home, places[Mascot.BUTTER])
    }

    /** Реплика уехала за край — стоять у неё значит висеть в пустоте. */
    @Test
    fun `реплика за краем экрана — домой`() {
        val places = mascotPlaces(listOf(greeting, over), shownKeys = setOf(greeting.key), chipOwner = null)

        assertEquals(MascotPlace.Home, places[Mascot.BUTTER])
        assertEquals(MascotPlace.Reply(greeting.key), places[Mascot.EGG])
    }

    @Test
    fun `на чипс прыгает хозяин макроса первой подсказки`() {
        val resolved = ResolvedItem("творог", candidate(prot = 1700, fat = 500, carb = 300), grams = 200, confidence = 1f, source = EntrySource.TEXT)

        assertEquals(Mascot.EGG, chipOwner(ComposerRow.Results(listOf(resolved))))
        assertEquals(Mascot.GRAIN, chipOwner(ComposerRow.Predictions(listOf(candidate(prot = 100, fat = 30, carb = 2300)))))
        assertNull("у веса макроса нет", chipOwner(ComposerRow.Weight(82_400)))
        assertNull(chipOwner(ComposerRow.None))
    }
}
