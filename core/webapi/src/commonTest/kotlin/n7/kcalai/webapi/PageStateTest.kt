package n7.kcalai.webapi

import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Страница дневника в браузере: что на ней видно и кто из персонажей говорит.
 *
 * Логика страницы — здесь, в общем коде, и проверяется тестом; сама страница
 * на Compose только рисует.
 */
class PageStateTest {

    private val buckwheat = WebEntry(7, "Гречка отварная", 200, MealType.LUNCH, 220, 840, 220, 4_260)
    private val kefir = WebEntry(9, "Кефир 1%", 250, MealType.BREAKFAST, 100, 750, 250, 1_000)
    private val day = WebDay(owner = "Женя", entries = listOf(buckwheat, kefir), goalKcal = 2_100, peers = emptyList())

    @Test
    fun `новый браузер видит код и Яйцо просит пустить на телефоне`() {
        val state = PageState.Connecting.after(ToPage.Pairing(token = "t", code = "4821"))

        assertEquals(PageState.Pairing("4821"), state)
        val line = state.line()!!
        assertEquals(Mascot.EGG, line.mascot)
        assertTrue("4821" in line.text, line.text)
    }

    @Test
    fun `пустили — виден дневник`() {
        val state = PageState.Pairing("4821").after(ToPage.Day(day))

        assertEquals(PageState.Diary(day), state)
        assertNull(state.line(), "дневник с записями говорит сам за себя")
    }

    @Test
    fun `отказ и переполненная очередь — словами Яйца`() {
        assertEquals(Mascot.EGG, PageState.Pairing("4821").after(ToPage.Denied).line()?.mascot)
        assertEquals(Mascot.EGG, PageState.Connecting.after(ToPage.Busy).line()?.mascot)
    }

    /** Связь пропала — утешает Масло и объясняет, что сделать: страница переподключится сама. */
    @Test
    fun `связь пропала — Масло говорит, что сделать`() {
        val line = PageState.Lost.line()!!
        assertEquals(Mascot.BUTTER, line.mascot)
        assertTrue("дневник" in line.text, line.text)
    }

    /** Вышел сам — страница больше не стучится в телефон, а Яйцо говорит, как вернуться. */
    @Test
    fun `вышедший браузер — Яйцо говорит, как вернуться`() {
        val line = PageState.Left.line()!!
        assertEquals(Mascot.EGG, line.mascot)
        assertTrue("обнови" in line.text, line.text)
    }

    @Test
    fun `подсказки — только к тому, что сейчас в строке`() {
        val found = ToPage.Suggestions("гречка", listOf(WebSuggestion("Гречка отварная", 200, 220)))
        val state = assertIs<PageState.Diary>(PageState.Diary(day).after(found))

        assertEquals(found.items, state.suggestionsFor("гречка"))
        assertEquals(found.items, state.suggestionsFor(" гречка "))
        assertTrue(state.suggestionsFor("гречка 1").isEmpty(), "ответ на старый запрос — не к этой строке")
    }

    @Test
    fun `обновлённый дневник подсказки не сбрасывает`() {
        val found = ToPage.Suggestions("гречка", listOf(WebSuggestion("Гречка отварная", 200, 220)))
        val state = PageState.Diary(day).after(found).after(ToPage.Day(day.copy(entries = listOf(buckwheat))))

        assertEquals(found.items, assertIs<PageState.Diary>(state).suggestionsFor("гречка"))
    }

    @Test
    fun `пустой дневник — Яйцо зовёт написать`() {
        assertEquals(Mascot.EGG, PageState.Diary(day.copy(entries = emptyList())).line()?.mascot)
    }

    /** Записи по приёмам — в порядке дня, как в ленте приложения, пустые приёмы не показываются. */
    @Test
    fun `записи по приёмам в порядке дня`() {
        assertEquals(
            listOf(MealType.BREAKFAST to listOf(kefir), MealType.LUNCH to listOf(buckwheat)),
            day.byMeal(),
        )
    }

    @Test
    fun `соседи — словами Зёрнышка`() {
        val near = day.copy(peers = listOf(WebPeer("Аня", "http://192.168.1.7:47800/")))

        val line = PageState.Diary(near).peersLine()!!
        assertEquals(Mascot.GRAIN, line.mascot)
        assertTrue("Аня" in line.text, line.text)
        assertNull(PageState.Diary(day).peersLine())
    }
}
