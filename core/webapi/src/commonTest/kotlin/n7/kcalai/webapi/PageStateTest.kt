package n7.kcalai.webapi

import n7.kcalai.model.FiberTotal
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** Связь пропала до дневника — утешает Масло и объясняет, что сделать: страница переподключится сама. */
    @Test
    fun `связь пропала — Масло говорит, что сделать`() {
        val line = PageState.Connecting.lost().line()!!
        assertEquals(Mascot.BUTTER, line.mascot)
        assertTrue("дневник" in line.text, line.text)
    }

    /**
     * Связь пропала посреди дневника — дневник остаётся как был, только неживой:
     * страница его приглушает, а Масло говорит поверх, что сделать. Вернулась связь —
     * дневник снова живой, с теми же подсказками.
     */
    @Test
    fun `обрыв не стирает дневник — он остаётся, но неживой`() {
        val found = ToPage.Suggestions("гречка", listOf(WebSuggestion("Гречка отварная", 200, 220, Mascot.GRAIN)))
        val open = PageState.Diary(day).after(found)

        val offline = assertIs<PageState.Diary>(open.lost())
        assertEquals(day, offline.day)
        assertTrue(offline.offline)
        assertEquals(Mascot.BUTTER, offline.offlineLine().mascot)

        val back = assertIs<PageState.Diary>(offline.after(ToPage.Day(day)))
        assertFalse(back.offline)
        assertEquals(found.items, back.suggestionsFor("гречка"))
    }

    /** Отказ и очередь — ответы, а не обрыв: при закрытом сокете они остаются на экране. */
    @Test
    fun `отказ остаётся отказом и после обрыва`() {
        assertEquals(PageState.Denied, PageState.Denied.lost())
        assertEquals(PageState.Busy, PageState.Busy.lost())
        assertEquals(PageState.Left, PageState.Left.lost())
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
        val found = ToPage.Suggestions("гречка", listOf(WebSuggestion("Гречка отварная", 200, 220, Mascot.GRAIN)))
        val state = assertIs<PageState.Diary>(PageState.Diary(day).after(found))

        assertEquals(found.items, state.suggestionsFor("гречка"))
        assertEquals(found.items, state.suggestionsFor(" гречка "))
        assertTrue(state.suggestionsFor("гречка 1").isEmpty(), "ответ на старый запрос — не к этой строке")
    }

    @Test
    fun `обновлённый дневник подсказки не сбрасывает`() {
        val found = ToPage.Suggestions("гречка", listOf(WebSuggestion("Гречка отварная", 200, 220, Mascot.GRAIN)))
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

    /**
     * Новая запись — хозяин её макроса подпрыгивает в своей плитке, как в приложении.
     * Первый показ дневника — не новость: подпрыгивать не за что.
     */
    @Test
    fun `новые записи — хозяева подпрыгивают`() {
        val fish = WebEntry(11, "Треска", 150, MealType.DINNER, 120, 2_600, 100, 0)
        val later = day.copy(entries = day.entries + fish)

        assertEquals(listOf(Mascot.EGG), later.arrivals(since = day))
        assertTrue(day.arrivals(since = null).isEmpty(), "первый показ — не новость")
        assertTrue(day.arrivals(since = day).isEmpty())
        assertEquals(Mascot.GRAIN, buckwheat.owner, "у гречки больше всего углеводов")
    }

    /** Клетчатка, неизвестная у части продуктов, — нижняя граница, а не ноль: «Кл 0» было бы неправдой. */
    @Test
    fun `клетчатка дня — известная плюс пробел у неизвестной`() {
        val withFiber = day.copy(entries = listOf(buckwheat.copy(fiberCg = 560), kefir))

        assertEquals(FiberTotal(cg = 560, unknown = 1), withFiber.fiber())
        assertEquals(FiberTotal(cg = 560, unknown = 0), listOf(buckwheat.copy(fiberCg = 560)).fiber())
    }

    @Test
    fun `итоги приёма — из его записей`() {
        assertEquals(220, listOf(buckwheat).totals().kcal)
    }

    /** Неделя — с понедельника по воскресенье, как в календаре; стрелки листают по неделе. */
    @Test
    fun `неделя дня — с понедельника по воскресенье`() {
        // 2026-11-24 — вторник.
        val tuesday = kotlinx.datetime.LocalDate(2026, 11, 24).toEpochDays()

        assertEquals(tuesday - 1..tuesday + 5, weekOf(tuesday))
        assertEquals(tuesday - 1, weekOf(tuesday - 1).first, "понедельник — сам начало недели")
    }

    @Test
    fun `в прошлый день приём по умолчанию — первый незаписанный`() {
        assertEquals(MealType.BREAKFAST, day.copy(entries = emptyList()).defaultMeal())
        assertEquals(MealType.DINNER, day.defaultMeal(), "завтрак и обед уже записаны")
    }

    @Test
    fun `цель из граммов складывает калории`() {
        assertEquals(120 * 4 + 70 * 9 + 230 * 4, WebMacros(prot = 120, fat = 70, carb = 230).kcal)
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
