package n7.kcalai.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Всё, что поиск по смыслу берёт из личных данных: кэш отпечатков и прошлые выборы.
 *
 * Журнал подтверждений пишет [PersonalDao], а читается он здесь — поиску нужна
 * своя выборка из него, а не строки журнала целиком.
 */
@Dao
interface SemanticDao {

    @Query("SELECT * FROM text_embedding WHERE model = :model")
    suspend fun embeddings(model: String): List<TextEmbeddingEntity>

    @Upsert
    suspend fun upsertEmbeddings(rows: List<TextEmbeddingEntity>)

    /** Отпечатки другой модели с нынешними несравнимы — место они только занимают. */
    @Query("DELETE FROM text_embedding WHERE model != :model")
    suspend fun dropOtherModels(model: String)

    /**
     * Что человек выбирал по каким запросам — по паре «запрос, выбор» одна строка.
     *
     * Свежие первыми: кэш досчитывает отпечатки понемногу, и начинать надо с нынешних
     * привычек, а не с прошлогодних.
     */
    @Query(
        "SELECT query, pickedRef, count(*) AS times, max(createdAt) AS lastAt FROM suggestion_event " +
            "GROUP BY query, pickedRef ORDER BY lastAt DESC LIMIT :limit"
    )
    suspend fun pastPicks(limit: Int): List<PastPickRow>
}
