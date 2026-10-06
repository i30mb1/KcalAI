package n7.kcalai.personal

import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Идея 1: дневник как язык — предсказание следующего продукта. */
class NextFoodModelTest {

    /**
     * Две недели человек завтракает овсянкой и запивает кофе, а ужинает курицей.
     *
     * Это и есть тот самый узкий личный словарь, на котором биграммы работают
     * там, где большой модели не хватило бы данных.
     *
     * Привычка заканчивается вчера: сегодняшний день тесты досыпают сами, потому
     * что «записано сегодня» модель трактует иначе, чем «ел две недели».
     */
    private fun fortnight(): List<DiaryEntryEntity> {
        val entries = mutableListOf<DiaryEntryEntity>()
        var id = 1L
        for (day in TODAY - 14..TODAY - 1) {
            entries += entry(id++, day, 8, MealType.BREAKFAST, "Овсянка", OATMEAL, grams = 250)
            entries += entry(id++, day, 8, MealType.BREAKFAST, "Кофе с молоком", COFFEE, grams = 200)
            entries += entry(id++, day, 19, MealType.DINNER, "Куриная грудка", CHICKEN, grams = 180)
        }
        return entries
    }

    private fun history(): FoodHistory = historyOf(fortnight())

    @Test
    fun `утром предлагается завтрак, а не ужин`() {
        val predictions = NextFoodModel.predict(
            history = history(),
            context = contextAt(hour = 8, meal = MealType.BREAKFAST),
        )

        assertEquals("Овсянка", predictions.first().displayName)
        assertTrue(
            "ужин не должен подниматься на завтраке",
            predictions.indexOfFirst { it.displayName == "Куриная грудка" } > 0,
        )
    }

    @Test
    fun `после овсянки предлагается кофе — это и есть модель переходов`() {
        val predictions = NextFoodModel.predict(
            history = history(),
            context = contextAt(
                hour = 8,
                meal = MealType.BREAKFAST,
                prevRefKey = genericKey(OATMEAL),
            ),
        )

        assertEquals("Кофе с молоком", predictions.first().displayName)
    }

    @Test
    fun `граммы берутся из личной медианы, а не из таблицы порций`() {
        val predictions = NextFoodModel.predict(
            history = history(),
            context = contextAt(hour = 8, meal = MealType.BREAKFAST),
        )

        assertEquals(250, predictions.first { it.displayName == "Овсянка" }.servingG)
    }

    @Test
    fun `на пустой истории модель молчит, а не выдумывает`() {
        // Одна запись, и та — не в сегодняшнем приёме: фильтр «съеденное сегодня»
        // её не убирает, молчит именно порог истории.
        val predictions = NextFoodModel.predict(
            history = historyOf(listOf(entry(1, TODAY - 1, 8, MealType.BREAKFAST, "Овсянка", OATMEAL))),
            context = contextAt(hour = 8, meal = MealType.BREAKFAST),
        )

        assertTrue(predictions.isEmpty())
    }

    /**
     * Порог — ровно на пяти записях, а не «где-то около».
     *
     * По двум записям модель уверенно предложит эти две, и человек решит,
     * что приложение сломано. Поэтому до пяти молчим совсем, с пяти — отвечаем.
     */
    @Test
    fun `порог истории — пять записей`() {
        fun predictAfter(days: Int): List<String> {
            val entries = (1..days).map { day ->
                entry(day.toLong(), TODAY - day, 8, MealType.BREAKFAST, "Овсянка", OATMEAL, grams = 250)
            }
            return NextFoodModel.predict(
                history = historyOf(entries),
                context = contextAt(hour = 8, meal = MealType.BREAKFAST),
            ).map { it.displayName }
        }

        assertTrue("по четырём записям предсказывать нечего", predictAfter(4).isEmpty())
        assertEquals(listOf("Овсянка"), predictAfter(5))
    }

    /**
     * Главная жалоба: ряд «обычно в это время» показывал только что введённое.
     *
     * Запись продукта раньше его же и поднимала — свежесть становилась максимальной
     * на самом тяжёлом весе. Человек записывал овсянку и тут же видел подсказку
     * «обычно в это время: овсянка» над пузырьком с этой овсянкой.
     */
    @Test
    fun `записанное в этот приём пищи больше не предлагается`() {
        val entries = fortnight() + entry(99, TODAY, 8, MealType.BREAKFAST, "Овсянка", OATMEAL, grams = 250)

        val predictions = NextFoodModel.predict(
            history = historyOf(entries),
            context = contextAt(hour = 8, meal = MealType.BREAKFAST),
        )

        assertTrue(
            "овсянка записана в этот завтрак и предлагаться не должна",
            predictions.none { it.displayName == "Овсянка" },
        )
    }

    /** Эхо последнего ввода: что записал, то и предложено первым. Этого быть не должно. */
    @Test
    fun `записанное не поднимается наверх ряда`() {
        // Куриная грудка — ужин, на завтраке ей не место. Записываем её в завтрак:
        // раньше максимальная свежесть вытаскивала её на первое место.
        val entries = fortnight() + entry(99, TODAY, 8, MealType.BREAKFAST, "Куриная грудка", CHICKEN)

        val predictions = NextFoodModel.predict(
            history = historyOf(entries),
            context = contextAt(hour = 8, meal = MealType.BREAKFAST),
        )

        assertEquals("Овсянка", predictions.first().displayName)
    }

    /**
     * Съеденное в другом приёме пищи опускается, но из ряда не исчезает.
     *
     * Кофе дважды в день — не ошибка, и запрещать его вечером тому, кто пил его
     * утром, значило бы править привычку вместо того, чтобы её угадывать.
     */
    @Test
    fun `съеденное в другой приём пищи опускается, но остаётся`() {
        val entries = fortnight() +
            entry(98, TODAY, 8, MealType.BREAKFAST, "Кофе с молоком", COFFEE, grams = 200)

        val predictions = NextFoodModel.predict(
            history = historyOf(entries),
            context = contextAt(hour = 19, meal = MealType.DINNER),
        )

        val coffee = predictions.indexOfFirst { it.displayName == "Кофе с молоком" }
        assertTrue("кофе должен остаться в ряду", coffee >= 0)
        assertTrue("но не первым — утром он уже был", coffee > 0)
    }

    /**
     * Штраф за «уже было сегодня» сильнее любого отдельного слагаемого.
     *
     * Курица — ужин, и на ужине она первая. Записанная сегодня в завтрак,
     * на том же ужине она обязана уступить место, но из ряда не исчезнуть:
     * съесть её второй раз — нормальный ответ, поэтому штраф, а не запрет.
     *
     * Здесь же проверяется, что сегодняшняя запись не добавляет свежести:
     * без нижней границы в один день `exp(0) = 1` вытаскивало записанное
     * на первое место вместо того, чтобы опускать.
     */
    @Test
    fun `съеденное сегодня уступает первое место, но остаётся в ряду`() {
        fun dinnerRow(chickenDay: Long): List<String> {
            val entries = fortnight() +
                entry(99, chickenDay, 8, MealType.BREAKFAST, "Куриная грудка", CHICKEN)
            return NextFoodModel.predict(
                history = historyOf(entries),
                context = contextAt(hour = 19, meal = MealType.DINNER),
            ).map { it.displayName }
        }

        // Вчерашний завтрак ужину не мешает: курица остаётся первой.
        assertEquals("Куриная грудка", dinnerRow(TODAY - 1).first())

        // А сегодняшний — опускает её в хвост, не выбрасывая.
        val today = dinnerRow(TODAY)
        assertTrue("курица уступает первое место", today.first() != "Куриная грудка")
        assertTrue("но остаётся доступной", today.contains("Куриная грудка"))
    }

    /**
     * Приём пищи весит больше часа.
     *
     * Овсянку и кофе человек ест в 8 утра, курицу — в 19:00. Если открыть ввод
     * в 8 утра и поправить приём на ужин, первой обязана стоять курица: час
     * говорит «завтрак», приём пищи — «ужин», и решает приём.
     */
    @Test
    fun `приём пищи важнее часа`() {
        val byMeal = NextFoodModel.predict(
            history = history(),
            context = contextAt(hour = 8, meal = MealType.DINNER),
        )
        assertEquals("Куриная грудка", byMeal.first().displayName)

        // Тот же час с честным завтраком даёт обратный порядок — значит,
        // разницу внёс именно приём пищи, а не час.
        val byBreakfast = NextFoodModel.predict(
            history = history(),
            context = contextAt(hour = 8, meal = MealType.BREAKFAST),
        )
        assertEquals("Овсянка", byBreakfast.first().displayName)
    }

    /** Весь день записан — предлагать нечего, и ряд честнее убрать совсем. */
    @Test
    fun `когда в этот приём пищи записано всё обычное, ряд пустеет`() {
        val entries = fortnight() +
            entry(97, TODAY, 8, MealType.BREAKFAST, "Овсянка", OATMEAL, grams = 250) +
            entry(98, TODAY, 8, MealType.BREAKFAST, "Кофе с молоком", COFFEE, grams = 200) +
            entry(99, TODAY, 8, MealType.BREAKFAST, "Куриная грудка", CHICKEN)

        val predictions = NextFoodModel.predict(
            history = historyOf(entries),
            context = contextAt(hour = 8, meal = MealType.BREAKFAST),
        )

        assertTrue(predictions.isEmpty())
    }

    /** Вчерашняя запись подсказку не глушит: «сегодня» — это именно сегодня. */
    @Test
    fun `вчерашняя запись предсказание не убирает`() {
        val predictions = NextFoodModel.predict(
            history = historyOf(fortnight()),
            context = contextAt(hour = 8, meal = MealType.BREAKFAST),
        )

        assertEquals("Овсянка", predictions.first().displayName)
    }

    private companion object {
        const val OATMEAL = 1L
        const val COFFEE = 2L
        const val CHICKEN = 3L
    }
}
