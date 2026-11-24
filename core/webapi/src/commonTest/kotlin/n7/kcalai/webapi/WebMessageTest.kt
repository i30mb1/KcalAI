package n7.kcalai.webapi

import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.PageGrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Сообщения между страницей и телефоном: что ушло, то и пришло.
 *
 * Обе стороны собраны из одного кода, но страница в браузере живёт своей жизнью:
 * вкладка, открытая неделю назад, говорит старой версией, а в сокет может
 * постучаться что угодно из той же сети. Поэтому чужое и битое не разбирается вовсе.
 */
class WebMessageTest {

    private val day = WebDay(
        owner = "Женя",
        entries = listOf(
            WebEntry(
                id = 7, name = "Гречка отварная", grams = 200, meal = MealType.LUNCH, kcal = 220, protCg = 840, fatCg = 220, carbCg = 4_260,
                fiberCg = 560, time = "13:05",
            ),
            WebEntry(id = 9, name = "Кефир 1%", grams = 250, meal = MealType.SNACK, kcal = 100, protCg = 750, fatCg = 250, carbCg = 1_000, time = "16:40"),
        ),
        goalKcal = 2_100,
        peers = listOf(WebPeer(name = "Аня", url = "http://192.168.1.7:47800/")),
        goalMacros = WebMacros(prot = 120, fat = 70, carb = 230),
        fiberGoal = 28,
        grace = PageGrace.MIN_15,
        date = 20_781,
        today = 20_782,
        week = listOf(WebDaySummary(date = 20_781, kcal = 320, goalKcal = 2_100), WebDaySummary(date = 20_782, kcal = 0, goalKcal = null)),
    )

    @Test
    fun `сообщения страницы доходят до телефона как были`() {
        val sent = listOf(
            ToPhone.Hello(token = null),
            ToPhone.Hello(token = "a1b2"),
            ToPhone.Search(query = "гречка 200"),
            ToPhone.Add(query = "гречка 200", index = 1, grams = 180),
            ToPhone.Add(query = "гречка 200", index = 0, grams = 200, meal = MealType.DINNER),
            ToPhone.Open(day = 20_775),
            ToPhone.SetGoal(prot = 120, fat = 70, carb = 230),
            ToPhone.SetName(name = "Женя"),
            ToPhone.Revoke(browserId = 4),
            ToPhone.SetGrace(PageGrace.FOREVER),
            ToPhone.SetGrams(entryId = 7, grams = 150),
            ToPhone.Delete(entryId = 9),
            ToPhone.Leave,
        )

        sent.forEach { message -> assertEquals(message, parseToPhone(encode(message))) }
    }

    @Test
    fun `сообщения телефона доходят до страницы как были`() {
        val sent = listOf(
            ToPage.Pairing(token = "a1b2", code = "4821"),
            ToPage.Denied,
            ToPage.Busy,
            ToPage.Day(day),
            ToPage.Day(day.copy(goalKcal = null, goalMacros = null, peers = emptyList(), entries = emptyList(), week = emptyList())),
            ToPage.Day(day.copy(browsers = listOf(WebBrowser(id = 4, label = "Chrome на Windows", here = true), WebBrowser(id = 5, label = "Safari на Mac", here = false)))),
            ToPage.Suggestions(
                query = "гречка",
                items = listOf(WebSuggestion(name = "Гречка отварная", grams = 200, kcal = 220, mascot = Mascot.GRAIN)),
            ),
        )

        sent.forEach { message -> assertEquals(message, parseToPage(encode(message))) }
    }

    @Test
    fun `итог дня складывается из записей`() {
        assertEquals(320, day.kcal)
        assertEquals(NutrimentTotals(kcal = 320, protCg = 1_590, fatCg = 470, carbCg = 5_260), day.totals())
    }

    /** Чужая версия, мусор и сообщение не той стороны — молча мимо, без падения. */
    @Test
    fun `чужое и битое не разбирается`() {
        assertNull(parseToPhone("не json"))
        assertNull(parseToPhone("""{"v":99,"t":"search","query":"гречка"}"""))
        assertNull(parseToPhone("""{"v":$WEB_VERSION,"t":"нет такого"}"""))
        assertNull(parseToPhone("""{"v":$WEB_VERSION,"t":"add","query":"гречка"}"""), "без номера и граммов добавлять нечего")
        assertNull(parseToPhone(encode(ToPage.Denied)), "сообщение телефона телефону не команда")
        assertNull(parseToPage("[]"))
    }

    /** Имя — подпись в чужой ленте: пустое не меняет ничего, длинное укорачивается. */
    @Test
    fun `имя со страницы — непустое и не длиннее подписи`() {
        assertNull(parseToPhone(encode(ToPhone.SetName("   "))))
        assertEquals(ToPhone.SetName("Женя"), parseToPhone(encode(ToPhone.SetName("  Женя "))))
        assertEquals(WEB_NAME_MAX, (parseToPhone(encode(ToPhone.SetName("я".repeat(200)))) as ToPhone.SetName).name.length)
    }

    /** Цель — те же пределы, что у шита цели: ноль белка и центнер углеводов не сохраняются. */
    @Test
    fun `невозможная цель не разбирается`() {
        assertNull(parseToPhone(encode(ToPhone.SetGoal(prot = 0, fat = 70, carb = 230))))
        assertNull(parseToPhone(encode(ToPhone.SetGoal(prot = 120, fat = 70, carb = 100_000))))
    }

    /** Граммы со страницы — от человека, но через сеть: ноль и десять тонн не записываются. */
    @Test
    fun `невозможные граммы не разбираются`() {
        assertNull(parseToPhone(encode(ToPhone.SetGrams(entryId = 7, grams = 0))))
        assertNull(parseToPhone(encode(ToPhone.Add(query = "гречка", index = 0, grams = 10_000_000))))
    }
}
