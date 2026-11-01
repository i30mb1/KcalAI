package n7.kcalai.notify

import n7.kcalai.mascot.Suggestion
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Кнопка «Записать: гречка» в шторке.
 *
 * Между уведомлением и записью в дневник подсказка живёт строками в extras
 * PendingIntent: процесс к тому времени мог умереть, и памяти о ней нет.
 */
class SuggestionExtrasTest {

    private val buckwheat = Suggestion(
        candidate = FoodCandidate(
            ref = FoodRef.Generic(7),
            displayName = "Гречка отварная",
            nutriments = Nutriments(kcal100 = 110, prot100 = 420, fat100 = 110, carb100 = 2_130),
            servingG = 200,
        ),
        meal = MealType.LUNCH,
    )

    @Test
    fun `подсказка переживает дорогу через PendingIntent`() {
        val extras = SuggestionExtras.encode(buckwheat, messageId = 42, mascot = Mascot.GRAIN)

        val decoded = SuggestionExtras.decode(extras)

        assertEquals(SuggestionExtras.Decoded(buckwheat, messageId = 42, mascot = Mascot.GRAIN), decoded)
    }

    @Test
    fun `битые extras — null, а не падение`() {
        val extras = SuggestionExtras.encode(buckwheat, messageId = 42, mascot = Mascot.GRAIN)

        assertNull(SuggestionExtras.decode(emptyMap()))
        assertNull(SuggestionExtras.decode(extras - "ref"))
        assertNull(SuggestionExtras.decode(extras + ("grams" to "много")))
        assertNull(SuggestionExtras.decode(extras + ("mascot" to "CHEESE")))
    }

    /** Без веса записывать нечего: «гречка» без граммов — не запись дневника. */
    @Test
    fun `без порции кнопки нет`() {
        val noServing = buckwheat.copy(candidate = buckwheat.candidate.copy(servingG = null))

        assertNull(SuggestionExtras.decode(SuggestionExtras.encode(noServing, messageId = 1, mascot = Mascot.EGG)))
    }
}
