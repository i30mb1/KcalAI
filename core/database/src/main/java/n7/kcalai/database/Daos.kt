package n7.kcalai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import n7.kcalai.model.MealType

@Dao
interface DiaryDao {

    @Insert
    suspend fun insert(entry: DiaryEntryEntity): Long

    @Insert
    suspend fun insertAll(entries: List<DiaryEntryEntity>): List<Long>

    @Query("SELECT * FROM diary_entry WHERE dateEpochDay = :dateEpochDay ORDER BY createdAt, id")
    fun observeDay(dateEpochDay: Long): Flow<List<DiaryEntryEntity>>

    @Query("DELETE FROM diary_entry WHERE id = :id")
    suspend fun delete(id: Long)

    /** Недавно добавленное — источник быстрых подсказок «повторить». */
    @Query("SELECT * FROM diary_entry ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DiaryEntryEntity>

    /**
     * Вся история за окно — сырьё для моделей персонализации.
     *
     * Порядок важен: модель переходов читает записи парами, а «что после чего»
     * определяется временем добавления внутри одного дня.
     */
    @Query("SELECT * FROM diary_entry WHERE dateEpochDay >= :fromEpochDay ORDER BY dateEpochDay, createdAt, id")
    suspend fun since(fromEpochDay: Long): List<DiaryEntryEntity>

    @Query("SELECT * FROM diary_entry WHERE id = :id")
    suspend fun findById(id: Long): DiaryEntryEntity?

    /** Правка веса уже добавленной записи. КБЖУ на 100 г остаётся снимком и не трогается. */
    @Query("UPDATE diary_entry SET grams = :grams WHERE id = :id")
    suspend fun updateGrams(id: Long, grams: Int)
}

@Dao
interface UserFoodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(food: UserFoodEntity): Long

    @Query("SELECT * FROM user_food WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): UserFoodEntity?

    @Query("SELECT * FROM user_food WHERE id = :id")
    suspend fun findById(id: Long): UserFoodEntity?

    @Query(
        "SELECT * FROM user_food WHERE name LIKE '%' || :query || '%' " +
            "ORDER BY length(name), createdAt DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<UserFoodEntity>
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
}

@Dao
interface BodyMetricDao {

    /** Взвешиваний за день может быть несколько, запись остаётся одна — последняя. */
    @Upsert
    suspend fun upsert(metric: BodyMetricEntity)

    @Query("SELECT * FROM body_metric WHERE dateEpochDay >= :fromEpochDay ORDER BY dateEpochDay")
    suspend fun since(fromEpochDay: Long): List<BodyMetricEntity>

    @Query("SELECT * FROM body_metric ORDER BY dateEpochDay DESC LIMIT 1")
    fun observeLatest(): Flow<BodyMetricEntity?>
}

/** Всё, что копится ради персонализации: журнал подтверждений, порции, веса модели. */
@Dao
interface PersonalDao {

    @Insert
    suspend fun insertEvent(event: SuggestionEventEntity): Long

    /** Необученный хвост журнала. Обучение инкрементально, переигрывать всё незачем. */
    @Query("SELECT * FROM suggestion_event WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun eventsAfter(afterId: Long, limit: Int): List<SuggestionEventEntity>

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
