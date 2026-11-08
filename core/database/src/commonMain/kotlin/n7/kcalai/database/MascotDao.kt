package n7.kcalai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Сообщения персонажей и их профиль человека. */
@Dao
interface MascotDao {

    @Insert
    suspend fun insert(message: MascotMessageEntity): Long

    /** Сообщения одного дня — в его переписку. */
    @Query("SELECT * FROM mascot_message WHERE dateEpochDay = :dateEpochDay ORDER BY createdAt, id")
    fun observeDay(dateEpochDay: Long): Flow<List<MascotMessageEntity>>

    /** Последние сообщения, новые первыми: память «не повторяться» и бюджет. */
    @Query("SELECT * FROM mascot_message ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MascotMessageEntity>

    @Query("SELECT * FROM mascot_message WHERE id = :id")
    suspend fun findById(id: Long): MascotMessageEntity?

    /** Когда в последний раз говорили на тему; `null` — не говорили никогда. */
    @Query("SELECT MAX(createdAt) FROM mascot_message WHERE topic = :topic")
    suspend fun lastSaid(topic: String): Long?

    /** Первое прочтение не перезаписывается: важно, когда человек увидел сообщение впервые. */
    @Query("UPDATE mascot_message SET openedAt = :at WHERE id = :id AND openedAt IS NULL")
    suspend fun markOpened(id: Long, at: Long)

    @Query("UPDATE mascot_message SET openedAt = :at WHERE openedAt IS NULL")
    suspend fun markAllOpened(at: Long)

    /**
     * Ответ на вопрос — во все его копии, что ещё ждут: неотвеченный вопрос задаётся
     * заново в новый день, и вчерашний ответ тот же, что сегодняшний.
     *
     * @return сколько вопросов получили ответ; ноль — ответ уже дан, второй не принимается
     */
    @Query("UPDATE mascot_message SET choice = :choice WHERE topic = :topic AND choice IS NULL")
    suspend fun answer(topic: String, choice: Choice): Int

    @Query("SELECT * FROM mascot_profile WHERE id = 0")
    fun observeProfile(): Flow<MascotProfileEntity?>

    @Query("SELECT * FROM mascot_profile WHERE id = 0")
    suspend fun profile(): MascotProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: MascotProfileEntity)
}
