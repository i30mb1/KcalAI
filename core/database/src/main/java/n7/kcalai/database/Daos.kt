package n7.kcalai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import n7.kcalai.model.MealType

@Dao
interface DiaryDao {

    @Insert
    suspend fun insert(entry: DiaryEntryEntity): Long

    @Query("SELECT * FROM diary_entry WHERE dateEpochDay = :dateEpochDay ORDER BY createdAt, id")
    fun observeDay(dateEpochDay: Long): Flow<List<DiaryEntryEntity>>

    @Query("DELETE FROM diary_entry WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Вся история за окно — сырьё для моделей персонализации.
     *
     * Порядок важен: модель переходов читает записи парами, а «что после чего»
     * определяется временем добавления внутри одного дня.
     */
    @Query("SELECT * FROM diary_entry WHERE dateEpochDay >= :fromEpochDay ORDER BY dateEpochDay, createdAt, id")
    suspend fun since(fromEpochDay: Long): List<DiaryEntryEntity>

    /**
     * То же окно, но потоком — для графика за неделю.
     *
     * Отдельно от [since] намеренно: модели персонализации читают историю разово
     * в момент пересчёта, а график обязан шевелиться от каждой добавленной записи.
     */
    @Query("SELECT * FROM diary_entry WHERE dateEpochDay >= :fromEpochDay ORDER BY dateEpochDay, createdAt, id")
    fun observeSince(fromEpochDay: Long): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entry WHERE id = :id")
    suspend fun findById(id: Long): DiaryEntryEntity?

    /** Правка веса уже добавленной записи. КБЖУ на 100 г остаётся снимком и не трогается. */
    @Query("UPDATE diary_entry SET grams = :grams WHERE id = :id")
    suspend fun updateGrams(id: Long, grams: Int)

    /**
     * Перенос записи в другой приём пищи.
     *
     * Правило по времени суток угадывает, и в 16:05 съеденный обед становится
     * ужином. Указание человека сильнее правила — и должно доезжать до `meal`,
     * потому что по нему считают все модели персонализации.
     */
    @Query("UPDATE diary_entry SET meal = :meal WHERE id = :id")
    suspend fun updateMeal(id: Long, meal: MealType)
}

@Dao
interface UserFoodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(food: UserFoodEntity): Long

    /**
     * Правка уже заведённого продукта — по первичному ключу, а не вставкой поверх.
     *
     * `REPLACE` по уникальному коду выглядит тем же самым, но удаляет строку
     * и вставляет новую, то есть меняет `id`. А `id` — это ссылка, по которой
     * личная история узнаёт продукт: пересохранив ту же пачку с поправленной
     * цифрой, человек молча обнулил бы всё, что модели о ней знают.
     */
    @Update
    suspend fun update(food: UserFoodEntity)

    @Query("SELECT * FROM user_food WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): UserFoodEntity?

    @Query("SELECT * FROM user_food WHERE id = :id")
    suspend fun findById(id: Long): UserFoodEntity?

    /**
     * Все свои продукты — отбор идёт в Kotlin, а не в SQL.
     *
     * Отбирать здесь было бы нечем. `LIKE` по-русски не работает: встроенный
     * в SQLite он складывает регистр только у латиницы, и «Начинка», заведённая
     * с этикетки, не находилась по набранному строчными «начинка» вовсе. FTS-таблица
     * ради своих продуктов — молотилка не по масштабу: их у человека десятки,
     * а правило поиска уже написано и живёт в `:core:fooddb` рядом с остальными.
     *
     * Порядок — от новых: человек ищет то, что завёл недавно. Окончательный
     * задаёт репозиторий, когда уже знает, что чему совпало.
     */
    @Query("SELECT * FROM user_food ORDER BY createdAt DESC")
    suspend fun all(): List<UserFoodEntity>
}

@Dao
interface CachedProductDao {

    /** Свежий ответ вытесняет прошлый: цифры на упаковке меняются, и наш кэш не архив. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: CachedProductEntity)

    @Query("SELECT * FROM cached_product WHERE gtin = :gtin LIMIT 1")
    suspend fun findByGtin(gtin: String): CachedProductEntity?
}

/**
 * Очередь вкладов.
 *
 * Читается только фоновой отправкой — экранов у неё нет и не предполагается:
 * человек отправляет продукт тем, что заполняет форму, и знать про очередь не должен.
 */
@Dao
interface ContributionDao {

    @Insert
    suspend fun insert(contribution: ContributionEntity): Long

    @Query("SELECT * FROM contribution WHERE sentAt IS NULL ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<ContributionEntity>

    /** Для пузырька «не отправлено» — экран показывает очередь, хоть и не управляет ею. */
    @Query("SELECT count(*) FROM contribution WHERE sentAt IS NULL")
    suspend fun pendingCount(): Int

    @Query("UPDATE contribution SET sentAt = :sentAt WHERE id IN (:ids)")
    suspend fun markSent(ids: List<Long>, sentAt: Long)
}

@Dao
interface GoalDao {

    @Upsert
    suspend fun upsert(goal: DailyGoalEntity)

    @Query(
        "SELECT * FROM daily_goal WHERE fromDateEpochDay <= :dateEpochDay " +
            "ORDER BY fromDateEpochDay DESC LIMIT 1"
    )
    suspend fun goalFor(dateEpochDay: Long): DailyGoalEntity?

    @Query("SELECT * FROM daily_goal WHERE fromDateEpochDay <= :dateEpochDay ORDER BY fromDateEpochDay DESC LIMIT 1")
    fun observeGoalFor(dateEpochDay: Long): Flow<DailyGoalEntity?>

    /**
     * Все цели по возрастанию даты — для графика недели.
     *
     * Целиком, а не за окно графика: цель, действовавшая в прошлый вторник, могла
     * быть поставлена месяц назад, и запрос «с прошлого вторника» её бы не увидел.
     * Строк здесь столько, сколько раз человек менял цель, то есть единицы.
     */
    @Query("SELECT * FROM daily_goal ORDER BY fromDateEpochDay")
    fun observeAll(): Flow<List<DailyGoalEntity>>
}

@Dao
interface BodyMetricDao {

    /** Взвешиваний за день может быть несколько, запись остаётся одна — последняя. */
    @Upsert
    suspend fun upsert(metric: BodyMetricEntity)

    @Query("SELECT * FROM body_metric WHERE dateEpochDay >= :fromEpochDay ORDER BY dateEpochDay")
    suspend fun since(fromEpochDay: Long): List<BodyMetricEntity>
}

/** Всё, что копится ради персонализации: журнал подтверждений, порции, веса модели. */
@Dao
interface PersonalDao {

    @Insert
    suspend fun insertEvent(event: SuggestionEventEntity): Long

    @Insert
    suspend fun insertPortionSample(sample: PortionSampleEntity)

    @Query(
        "SELECT * FROM portion_sample WHERE refKey = :refKey " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun portionSamples(refKey: String, limit: Int): List<PortionSampleEntity>

    /**
     * Выбрасывает всё, кроме [keep] последних наблюдений продукта.
     *
     * Медиана всё равно считается по последним десяти, а таблица без прополки
     * растёт линейно по числу подтверждений и никогда не перестаёт.
     */
    @Query(
        "DELETE FROM portion_sample WHERE refKey = :refKey AND id NOT IN " +
            "(SELECT id FROM portion_sample WHERE refKey = :refKey ORDER BY createdAt DESC LIMIT :keep)"
    )
    suspend fun trimPortionSamples(refKey: String, keep: Int)

    @Query("SELECT * FROM ranker_weight")
    suspend fun rankerWeights(): List<RankerWeightEntity>

    @Upsert
    suspend fun upsertRankerWeights(weights: List<RankerWeightEntity>)

    @Query("SELECT * FROM ranker_state WHERE id = 0")
    suspend fun rankerState(): RankerStateEntity?

    @Upsert
    suspend fun upsertRankerState(state: RankerStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun dismissMealGap(dismiss: MealGapDismissEntity)

    @Query("SELECT meal FROM meal_gap_dismiss WHERE dateEpochDay = :dateEpochDay")
    suspend fun dismissedMeals(dateEpochDay: Long): List<MealType>

    /** Вчерашние отметки «пропустил» бесполезны — чистим при первом обращении за день. */
    @Query("DELETE FROM meal_gap_dismiss WHERE dateEpochDay < :beforeEpochDay")
    suspend fun trimDismissed(beforeEpochDay: Long)
}
