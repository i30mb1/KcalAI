package n7.kcalai.repositories

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import n7.kcalai.database.ContributionDao
import n7.kcalai.database.ContributionEntity
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryDao
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.GoalDao
import n7.kcalai.database.totals
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Nutriments
import n7.kcalai.model.parseFoodRef
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.plus
import n7.kcalai.model.serialize

/** Записи дня вместе с посчитанными итогами. */
data class DayTotals(
    val entries: List<DiaryEntryEntity>,
    val totals: NutrimentTotals,
)

/**
 * Калории одного дня — точка графика за неделю.
 *
 * Только калории: график отвечает на вопрос «как я шёл всю неделю», и макросы
 * на нём превратились бы во второй масштаб на одной оси, то есть в выдуманную
 * связь между величинами разного порядка.
 */
data class DaySummary(
    val dateEpochDay: Long,
    val kcal: Int,
    /**
     * Цель, действовавшая именно в этот день, — `null`, если её тогда не было.
     *
     * Своя у каждого дня, а не одна общая на график: цель версионируется датой,
     * и рисовать сегодняшние 2100 поверх недели, когда полнедели было 1800,
     * значило бы задним числом переписать историю на глазах у человека.
     */
    val goalKcal: Int? = null,
)

class DiaryRepository(
    private val diaryDao: DiaryDao,
    private val goalDao: GoalDao,
    private val contributionDao: ContributionDao,
) {

    /** Кладёт в дневник снимок КБЖУ, а не ссылку: обновление справочника не трогает историю. */
    suspend fun add(
        candidate: FoodCandidate,
        grams: Int,
        meal: MealType,
        date: Long,
        source: EntrySource,
        now: Long,
    ): Long = diaryDao.insert(candidate.toEntity(grams, meal, date, source, now))

    /**
     * То же, но еда пришла снимком и ссылки на продукт у неё нет.
     *
     * Так приходит предложение с телефона рядом: ссылка вида `user:7` — номер
     * строки в чужой базе, и у нас под тем же номером лежит другая еда. Записи
     * ссылка не нужна — она только для «повторить», и поле её nullable.
     */
    suspend fun addSnapshot(
        displayName: String,
        nutriments: Nutriments,
        grams: Int,
        meal: MealType,
        date: Long,
        source: EntrySource,
        now: Long,
        foodRef: String? = null,
    ): Long = diaryDao.insert(
        DiaryEntryEntity(
            dateEpochDay = date,
            meal = meal,
            displayName = displayName,
            grams = grams,
            kcal100 = nutriments.kcal100,
            prot100 = nutriments.prot100,
            fat100 = nutriments.fat100,
            carb100 = nutriments.carb100,
            source = source,
            foodRef = foodRef,
            createdAt = now,
            fiber100 = nutriments.fiber100,
        )
    )

    /** Запись как она легла в дневник — персонажам, чтобы отозваться на неё. */
    suspend fun entry(id: Long): DiaryEntryEntity? = diaryDao.findById(id)

    fun observeDay(date: Long): Flow<DayTotals> =
        diaryDao.observeDay(date).map { entries ->
            DayTotals(entries = entries, totals = entries.sumTotals())
        }

    /**
     * Калории по дням за последние [days] суток, включая сегодня.
     *
     * Ряд всегда полной длины: день без записей это ноль, а не пропуск. «Я ничего
     * не записал в четверг» и «четверга не было» — разные утверждения, и решать,
     * какое из них показывать, должен слой данных, а не композиция.
     *
     * Суммирование идёт поэлементно через [totals], а не `SUM()` в SQL. Итог дня
     * в шапке экрана считается так же, с округлением на каждой записи; агрегат
     * в SQL округлял бы один раз в конце и расходился бы с шапкой на единицы
     * килокалорий — в одном экране, на глазах у человека.
     */
    fun observeWeek(todayEpochDay: Long, days: Int = 7): Flow<List<DaySummary>> {
        val from = todayEpochDay - (days - 1)
        return combine(diaryDao.observeSince(from), goalDao.observeAll()) { entries, goals ->
            val byDay = entries.groupBy { it.dateEpochDay }
            (from..todayEpochDay).map { day ->
                DaySummary(
                    dateEpochDay = day,
                    kcal = byDay[day]?.sumTotals()?.kcal ?: 0,
                    // Цели отсортированы по возрастанию, поэтому последняя из тех,
                    // что начались не позже этого дня, и есть действовавшая.
                    goalKcal = goals.lastOrNull { it.fromDateEpochDay <= day }
                        ?.kcal
                        ?.takeIf { it > 0 },
                )
            }
        }
    }

    suspend fun delete(id: Long) = diaryDao.delete(id)

    /**
     * Отмена удаления: запись возвращается снимком целиком — с прежним id,
     * приёмом пищи и временем. Добавленная заново встала бы в ленте по времени
     * возврата, и обед, удалённый по ошибке вечером, вернулся бы ужином.
     */
    suspend fun restore(entry: DiaryEntryEntity) {
        diaryDao.insert(entry)
    }

    /** Человек сказал, что это был обед, а не ужин. Правило по времени тут ни при чём. */
    suspend fun moveToMeal(id: Long, meal: MealType) = diaryDao.updateMeal(id, meal)

    /**
     * Человек поправил сам продукт: сверил с упаковкой название и цифры.
     *
     * Снимок записи меняется сразу. Если у продукта есть штрих-код, исправление
     * уходит в очередь вкладов — остальным с тем же кодом станет верно. Без кода
     * отправить некуда, поэтому правка остаётся в дневнике. Нетронутое не
     * отправляется: вклад — подтверждение человека, а не повтор того, что было.
     * Пустое название продукт не стирает — остаётся прежнее.
     */
    suspend fun correctProduct(entry: DiaryEntryEntity, name: String, nutriments: Nutriments, now: Long) {
        val newName = name.trim().ifBlank { entry.displayName }
        val unchanged = entry.displayName == newName &&
            entry.kcal100 == nutriments.kcal100 &&
            entry.prot100 == nutriments.prot100 &&
            entry.fat100 == nutriments.fat100 &&
            entry.carb100 == nutriments.carb100 &&
            entry.fiber100 == nutriments.fiber100
        if (unchanged) return

        diaryDao.updateProduct(
            id = entry.id,
            name = newName,
            kcal = nutriments.kcal100,
            prot = nutriments.prot100,
            fat = nutriments.fat100,
            carb = nutriments.carb100,
            fiber = nutriments.fiber100,
        )

        val gtin = (parseFoodRef(entry.foodRef) as? FoodRef.Barcode)?.gtin ?: return
        contributionDao.insert(
            ContributionEntity(
                gtin = gtin,
                name = newName,
                kcal100 = nutriments.kcal100,
                prot100 = nutriments.prot100,
                fat100 = nutriments.fat100,
                carb100 = nutriments.carb100,
                servingG = null,
                createdAt = now,
                fiber100 = nutriments.fiber100,
            )
        )
    }

    suspend fun goalFor(date: Long): DailyGoalEntity? = goalDao.goalFor(date)

    fun observeGoal(date: Long): Flow<DailyGoalEntity?> = goalDao.observeGoalFor(date)

    suspend fun setGoal(goal: DailyGoalEntity) = goalDao.upsert(goal)
}

private fun FoodCandidate.toEntity(
    grams: Int,
    meal: MealType,
    date: Long,
    source: EntrySource,
    now: Long,
): DiaryEntryEntity = DiaryEntryEntity(
    id = 0,
    dateEpochDay = date,
    meal = meal,
    displayName = displayName,
    grams = grams,
    kcal100 = nutriments.kcal100,
    prot100 = nutriments.prot100,
    fat100 = nutriments.fat100,
    carb100 = nutriments.carb100,
    source = source,
    foodRef = ref.serialize(),
    createdAt = now,
    fiber100 = nutriments.fiber100,
)

private fun List<DiaryEntryEntity>.sumTotals(): NutrimentTotals =
    fold(NutrimentTotals.ZERO) { acc, entry -> acc + entry.totals() }
