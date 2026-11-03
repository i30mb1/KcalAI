package n7.kcalai.assistant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments

class DiaryToolsTest {

    private val borscht = FoodCandidate(
        ref = FoodRef.Generic(12),
        displayName = "Борщ",
        nutriments = Nutriments(kcal100 = 49, prot100 = 110, fat100 = 220, carb100 = 670),
        servingG = 300,
    )
    private val bread = FoodCandidate(
        ref = FoodRef.Generic(31),
        displayName = "Хлеб ржаной",
        nutriments = Nutriments(kcal100 = 210, prot100 = 680, fat100 = 120, carb100 = 4_000),
        servingG = 30,
    )

    private val today = 20_000L
    private val entries = mutableMapOf<Long, List<DiaryEntryEntity>>()
    private var currentGoal: DailyGoalEntity? = null

    private val tools = DiaryTools(
        search = { query, _ ->
            listOf(borscht, bread).filter { it.displayName.contains(query, ignoreCase = true) }
        },
        day = { entries[it].orEmpty() },
        goal = { currentGoal },
        today = { today },
    )

    private fun entry(day: Long, name: String, grams: Int, kcal100: Int, meal: MealType = MealType.LUNCH) =
        DiaryEntryEntity(
            dateEpochDay = day,
            meal = meal,
            displayName = name,
            grams = grams,
            kcal100 = kcal100,
            prot100 = 1_000,
            fat100 = 500,
            carb100 = 2_000,
            source = EntrySource.TEXT,
            foodRef = null,
            createdAt = 0,
        )

    // --- Поиск ---------------------------------------------------------------------------

    @Test
    fun `поиск отдаёт модели ключ продукта и цифры из базы`() = runTest {
        val found = tools.searchFood("борщ")

        assertTrue("generic:12" in found, found)
        assertTrue("Борщ" in found, found)
        assertTrue("49 ккал" in found, found)
        assertTrue("порция 300 г" in found, found)
    }

    @Test
    fun `пустой поиск говорит модели, что искать заново`() = runTest {
        val found = tools.searchFood("хрючево")

        assertTrue("ничего" in found, found)
        assertFalse("generic:" in found, found)
    }

    // --- Предложение ---------------------------------------------------------------------

    @Test
    fun `предложение продукта из выдачи становится чипсом с весом от модели`() = runTest {
        tools.searchFood("борщ")

        val item = tools.proposal("propose_food", mapOf("foodId" to "generic:12", "grams" to 350.0, "phrase" to "тарелка борща"))

        assertNotNull(item)
        assertEquals(borscht, item.candidate)
        assertEquals(350, item.grams)
        assertEquals("тарелка борща", item.sourceText)
        assertEquals(EntrySource.LLM, item.source)
        // Вес назвала модель, а не человек: в память личных порций он не идёт.
        assertTrue(item.gramsGuessed)
    }

    @Test
    fun `вес приходит из модели и строкой, и целым`() = runTest {
        tools.searchFood("хлеб")

        assertEquals(60, tools.proposal("propose_food", mapOf("foodId" to "generic:31", "grams" to "60"))?.grams)
        assertEquals(60, tools.proposal("propose_food", mapOf("foodId" to "generic:31", "grams" to 60L))?.grams)
        assertEquals(60, tools.proposal("propose_food", mapOf("foodId" to "generic:31", "grams" to 60))?.grams)
    }

    @Test
    fun `имя инструмента бывает полным — с пакетом и классом`() = runTest {
        tools.searchFood("хлеб")

        val item = tools.proposal(
            "n7.kcalai.assistant.KcalTools#propose_food",
            mapOf("foodId" to "generic:31", "grams" to 30),
        )

        assertEquals(bread, item?.candidate)
    }

    @Test
    fun `продукт, которого поиск не показывал, не предлагается — модель не выдумывает`() = runTest {
        tools.searchFood("борщ")

        assertNull(tools.proposal("propose_food", mapOf("foodId" to "generic:31", "grams" to 30)))
        assertNull(tools.proposal("propose_food", mapOf("foodId" to "generic:999", "grams" to 30)))
    }

    @Test
    fun `невозможный вес не предлагается`() = runTest {
        tools.searchFood("борщ")

        assertNull(tools.proposal("propose_food", mapOf("foodId" to "generic:12", "grams" to 0)))
        assertNull(tools.proposal("propose_food", mapOf("foodId" to "generic:12", "grams" to -5)))
        assertNull(tools.proposal("propose_food", mapOf("foodId" to "generic:12", "grams" to 25_000)))
        assertNull(tools.proposal("propose_food", mapOf("foodId" to "generic:12", "grams" to "много")))
    }

    @Test
    fun `вызов другого инструмента предложением не считается`() = runTest {
        tools.searchFood("борщ")

        assertNull(tools.proposal("search_food", mapOf("foodId" to "generic:12", "grams" to 300)))
    }

    @Test
    fun `без фразы чипс подписан названием продукта`() = runTest {
        tools.searchFood("борщ")

        assertEquals("Борщ", tools.proposal("propose_food", mapOf("foodId" to "generic:12", "grams" to 300))?.sourceText)
    }

    // --- Дневник -------------------------------------------------------------------------

    @Test
    fun `сводка дней считает итоги и остаток до цели сама — арифметику модели не доверяем`() = runTest {
        entries[today] = listOf(entry(today, "Гречка", 200, kcal100 = 110), entry(today, "Курица", 150, kcal100 = 200))
        entries[today - 1] = listOf(entry(today - 1, "Овсянка", 250, kcal100 = 90))
        currentGoal = DailyGoalEntity(fromDateEpochDay = 0, kcal = 2_000, prot = 120, fat = 70, carb = 220)

        val summary = tools.diaryDays(2)

        // 200 г × 110 + 150 г × 200 = 220 + 300 = 520 ккал; белок 35 г из 120.
        assertTrue("сегодня: 520 ккал" in summary, summary)
        assertTrue("осталось 1480 ккал" in summary, summary)
        assertTrue("белка 85 г" in summary, summary)
        assertTrue("вчера: 225 ккал" in summary, summary)
    }

    @Test
    fun `день без записей так и называется, а не нулями`() = runTest {
        val summary = tools.diaryDays(1)

        assertTrue("сегодня: записей нет" in summary, summary)
    }

    @Test
    fun `сводка не уходит дальше двух недель`() = runTest {
        val summary = tools.diaryDays(400)

        assertEquals(14, summary.lines().count { it.isNotBlank() })
    }

    @Test
    fun `записи дня — с приёмом, весом и калориями`() = runTest {
        entries[today - 1] = listOf(
            entry(today - 1, "Овсянка", 250, kcal100 = 90, meal = MealType.BREAKFAST),
            entry(today - 1, "Борщ", 300, kcal100 = 49, meal = MealType.LUNCH),
        )

        val listed = tools.dayEntries(1)

        assertTrue("Завтрак: Овсянка 250 г, 225 ккал" in listed, listed)
        assertTrue("Обед: Борщ 300 г, 147 ккал" in listed, listed)
    }
}
