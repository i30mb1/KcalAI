package n7.kcalai.repositories

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import n7.kcalai.database.ContributionDao
import n7.kcalai.database.ContributionEntity
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryDao
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.GoalDao
import n7.kcalai.database.nutriments
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * График недели и версионирование цели.
 *
 * Оба правила живут в репозитории, а не в SQL: ряд всегда полной длины, цель
 * у каждого дня своя. Room in-memory потребовал бы устройства, а проверяется
 * здесь не SQL, а сборка ряда поверх того, что SQL вернул.
 */
class DiaryRepositoryTest {

    private val diaryDao = FakeDiaryDao()
    private val goalDao = FakeGoalDao()
    private val contributions = FakeContributionDao()
    private val repository = DiaryRepository(diaryDao, goalDao, contributions)

    private val oatmeal = FoodCandidate(
        ref = FoodRef.Generic(1),
        displayName = "Овсянка",
        nutriments = Nutriments(kcal100 = 100, prot100 = 300, fat100 = 200, carb100 = 1500),
        servingG = 250,
    )

    private suspend fun add(grams: Int, date: Long, meal: MealType = MealType.BREAKFAST, now: Long = 1) {
        repository.add(oatmeal, grams = grams, meal = meal, date = date, source = EntrySource.TEXT, now = now)
    }

    @Test
    fun `неделя всегда из семи дней пустой день — ноль — не пропуск`() = runBlocking {
        add(grams = 200, date = TODAY)
        add(grams = 100, date = TODAY - 3, meal = MealType.LUNCH)

        val week = repository.observeWeek(TODAY).first()

        assertEquals(7, week.size)
        assertEquals((TODAY - 6..TODAY).toList(), week.map { it.dateEpochDay })
        assertEquals(listOf(0, 0, 0, 100, 0, 0, 200), week.map { it.kcal })
    }

    @Test
    fun `цель у каждого дня та что действовала тогда`() = runBlocking {
        goalDao.upsert(DailyGoalEntity(fromDateEpochDay = TODAY - 10, kcal = 1800, prot = 100, fat = 60, carb = 200))
        goalDao.upsert(DailyGoalEntity(fromDateEpochDay = TODAY - 2, kcal = 2100, prot = 120, fat = 70, carb = 230))

        val week = repository.observeWeek(TODAY).first()

        // Смена цели позавчера не переписывает задним числом начало недели.
        assertEquals(listOf(1800, 1800, 1800, 1800, 2100, 2100, 2100), week.map { it.goalKcal })
    }

    @Test
    fun `до первой цели дни идут без цели`() = runBlocking {
        goalDao.upsert(DailyGoalEntity(fromDateEpochDay = TODAY, kcal = 2000, prot = 100, fat = 60, carb = 200))

        val week = repository.observeWeek(TODAY).first()

        assertNull(week[0].goalKcal)
        assertEquals(2000, week[6].goalKcal)
    }

    @Test
    fun `итог дня — сумма записей с округлением на каждой`() = runBlocking {
        // 100 ккал/100 г: 33 г дают 33, 66 г — 66; вместе 99, а не округлённые 100.
        add(grams = 33, date = TODAY, now = 1)
        add(grams = 66, date = TODAY, now = 2)

        val day = repository.observeDay(TODAY).first()

        assertEquals(2, day.entries.size)
        assertEquals(99, day.totals.kcal)
    }

    @Test
    fun `в дневник ложится снимок КБЖУ — не ссылка`() = runBlocking {
        add(grams = 200, date = TODAY)

        val entry = repository.observeDay(TODAY).first().entries.single()

        assertEquals(100, entry.kcal100)
        assertEquals("generic:1", entry.foodRef)
        assertEquals(200, entry.grams)
    }

    /** Клетчатка ложится в снимок вместе с БЖУ, а неизвестная так и остаётся неизвестной. */
    @Test
    fun `клетчатка в снимке записи неизвестная — null`() = runBlocking {
        val bran = oatmeal.copy(nutriments = oatmeal.nutriments.copy(fiber100 = 1540))
        repository.add(bran, grams = 30, meal = MealType.BREAKFAST, date = TODAY, source = EntrySource.TEXT, now = 1)
        add(grams = 200, date = TODAY, now = 2)

        val entries = repository.observeDay(TODAY).first().entries

        assertEquals(listOf(1540, null), entries.map { it.fiber100 })
        assertEquals(1540, entries.first().nutriments().fiber100)
    }

    /**
     * Отмена удаления возвращает ту же запись, а не похожую: с прежним id,
     * приёмом пищи, весом и временем. Новая запись встала бы в ленте по времени
     * возврата — обед, удалённый по ошибке вечером, вернулся бы ужином.
     */
    @Test
    fun `удалённая запись возвращается той же самой`() = runBlocking {
        add(grams = 150, date = TODAY, meal = MealType.LUNCH, now = 1_000)
        val deleted = repository.observeDay(TODAY).first().entries.single()
        repository.delete(deleted.id)

        repository.restore(deleted)

        assertEquals(listOf(deleted), repository.observeDay(TODAY).first().entries)
    }

    /** Человек поправил КБЖУ продукта с кодом: снимок в дневнике и вклад в очереди. */
    @Test
    fun `исправленные КБЖУ продукта с кодом уходят в очередь вкладов`() = runBlocking {
        val barcoded = oatmeal.copy(ref = FoodRef.Barcode("4600699500001"))
        repository.add(barcoded, grams = 200, meal = MealType.BREAKFAST, date = TODAY, source = EntrySource.TEXT, now = 1)
        val entry = repository.observeDay(TODAY).first().entries.single()

        repository.correctProduct(entry, entry.displayName, Nutriments(kcal100 = 120, prot100 = 300, fat100 = 200, carb100 = 1400), now = 5)

        val saved = repository.observeDay(TODAY).first().entries.single()
        assertEquals(120, saved.kcal100)
        assertEquals(1400, saved.carb100)
        val sent = contributions.queued.single()
        assertEquals("4600699500001", sent.gtin)
        assertEquals("Овсянка", sent.name)
        assertEquals(120, sent.kcal100)
        assertEquals(1400, sent.carb100)
    }

    /** У продукта без кода отправить некуда: правка остаётся в дневнике. */
    @Test
    fun `исправление продукта без кода остаётся в дневнике`() = runBlocking {
        add(grams = 200, date = TODAY)
        val entry = repository.observeDay(TODAY).first().entries.single()

        repository.correctProduct(entry, entry.displayName, Nutriments(kcal100 = 120, prot100 = 300, fat100 = 200, carb100 = 1400), now = 5)

        assertEquals(120, repository.observeDay(TODAY).first().entries.single().kcal100)
        assertTrue(contributions.queued.isEmpty())
    }

    /** Клетчатка — такая же часть КБЖУ: правка только её тоже уходит вкладом. */
    @Test
    fun `клетчатка продукта с кодом уходит вкладом вместе с правкой`() = runBlocking {
        val barcoded = oatmeal.copy(ref = FoodRef.Barcode("4600699500001"))
        repository.add(barcoded, grams = 200, meal = MealType.BREAKFAST, date = TODAY, source = EntrySource.TEXT, now = 1)
        val entry = repository.observeDay(TODAY).first().entries.single()

        repository.correctProduct(
            entry,
            entry.displayName,
            Nutriments(kcal100 = 100, prot100 = 300, fat100 = 200, carb100 = 1500, fiber100 = 8000),
            now = 5,
        )

        assertEquals(8000, repository.observeDay(TODAY).first().entries.single().fiber100)
        assertEquals(8000, contributions.queued.single().fiber100)
    }

    /** Нетронутые значения не подтверждаются от имени человека: вклад не уходит. */
    @Test
    fun `нетронутые КБЖУ не попадают в очередь`() = runBlocking {
        val barcoded = oatmeal.copy(ref = FoodRef.Barcode("4600699500001"))
        repository.add(barcoded, grams = 200, meal = MealType.BREAKFAST, date = TODAY, source = EntrySource.TEXT, now = 1)
        val entry = repository.observeDay(TODAY).first().entries.single()

        repository.correctProduct(entry, entry.displayName, Nutriments(kcal100 = 100, prot100 = 300, fat100 = 200, carb100 = 1500), now = 5)

        assertTrue(contributions.queued.isEmpty())
    }

    /** Название — тоже знание о продукте: переименованный уходит вкладом под новым именем. */
    @Test
    fun `переименованный продукт с кодом уходит вкладом под новым именем`() = runBlocking {
        val barcoded = oatmeal.copy(ref = FoodRef.Barcode("4600699500001"))
        repository.add(barcoded, grams = 200, meal = MealType.BREAKFAST, date = TODAY, source = EntrySource.TEXT, now = 1)
        val entry = repository.observeDay(TODAY).first().entries.single()

        repository.correctProduct(entry, "  Овсяные хлопья Геркулес ", entry.nutriments(), now = 5)

        assertEquals("Овсяные хлопья Геркулес", repository.observeDay(TODAY).first().entries.single().displayName)
        assertEquals("Овсяные хлопья Геркулес", contributions.queued.single().name)
    }

    /** Пустое название продукт не стирает: остаётся прежнее. */
    @Test
    fun `пустое название оставляет прежнее`() = runBlocking {
        add(grams = 200, date = TODAY)
        val entry = repository.observeDay(TODAY).first().entries.single()

        repository.correctProduct(entry, "  ", entry.nutriments().copy(kcal100 = 120), now = 5)

        val saved = repository.observeDay(TODAY).first().entries.single()
        assertEquals("Овсянка", saved.displayName)
        assertEquals(120, saved.kcal100)
    }

    private companion object {
        const val TODAY = 20_000L
    }
}

private class FakeContributionDao : ContributionDao {

    val queued = mutableListOf<ContributionEntity>()

    override suspend fun insert(contribution: ContributionEntity): Long {
        queued += contribution
        return queued.size.toLong()
    }

    override suspend fun pending(limit: Int): List<ContributionEntity> = queued.take(limit)

    override suspend fun pendingCount(): Int = queued.size

    override suspend fun markSent(ids: List<Long>, sentAt: Long) = Unit
}

private class FakeDiaryDao : DiaryDao {

    private val entries = MutableStateFlow<List<DiaryEntryEntity>>(emptyList())
    private var nextId = 1L

    /** Как Room с автоключом: ноль — выдать новый id, иначе вставить с переданным. */
    override suspend fun insert(entry: DiaryEntryEntity): Long {
        val id = if (entry.id != 0L) entry.id else nextId++
        nextId = maxOf(nextId, id + 1)
        entries.value += entry.copy(id = id)
        return id
    }

    override fun observeDay(dateEpochDay: Long): Flow<List<DiaryEntryEntity>> =
        entries.map { all -> all.filter { it.dateEpochDay == dateEpochDay }.sortedWith(ORDER) }

    override suspend fun delete(id: Long) {
        entries.value = entries.value.filter { it.id != id }
    }

    override suspend fun since(fromEpochDay: Long): List<DiaryEntryEntity> =
        entries.value.filter { it.dateEpochDay >= fromEpochDay }.sortedWith(ORDER)

    override fun observeSince(fromEpochDay: Long): Flow<List<DiaryEntryEntity>> =
        entries.map { all -> all.filter { it.dateEpochDay >= fromEpochDay }.sortedWith(ORDER) }

    override suspend fun findById(id: Long): DiaryEntryEntity? = entries.value.find { it.id == id }

    override suspend fun updateGrams(id: Long, grams: Int) {
        entries.value = entries.value.map { if (it.id == id) it.copy(grams = grams) else it }
    }

    override suspend fun updateMeal(id: Long, meal: MealType) {
        entries.value = entries.value.map { if (it.id == id) it.copy(meal = meal) else it }
    }

    override suspend fun updateProduct(id: Long, name: String, kcal: Int, prot: Int, fat: Int, carb: Int, fiber: Int?) {
        entries.value = entries.value.map {
            if (it.id == id) it.copy(displayName = name, kcal100 = kcal, prot100 = prot, fat100 = fat, carb100 = carb, fiber100 = fiber) else it
        }
    }

    private companion object {
        val ORDER = compareBy<DiaryEntryEntity>({ it.dateEpochDay }, { it.createdAt }, { it.id })
    }
}

private class FakeGoalDao : GoalDao {

    private val goals = MutableStateFlow<Map<Long, DailyGoalEntity>>(emptyMap())

    override suspend fun upsert(goal: DailyGoalEntity) {
        goals.value += goal.fromDateEpochDay to goal
    }

    override suspend fun goalFor(dateEpochDay: Long): DailyGoalEntity? =
        goals.value.values.filter { it.fromDateEpochDay <= dateEpochDay }.maxByOrNull { it.fromDateEpochDay }

    override fun observeGoalFor(dateEpochDay: Long): Flow<DailyGoalEntity?> =
        goals.map { all -> all.values.filter { it.fromDateEpochDay <= dateEpochDay }.maxByOrNull { it.fromDateEpochDay } }

    override fun observeAll(): Flow<List<DailyGoalEntity>> =
        goals.map { all -> all.values.sortedBy { it.fromDateEpochDay } }
}
