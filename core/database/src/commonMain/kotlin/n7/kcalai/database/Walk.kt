package n7.kcalai.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Прогулка по беговой дорожке. Шаги приходят со страницы дневника: к дорожке по Bluetooth
 * подключается браузер, а хранит прогулки телефон — они видны и в приложении, и с любого компьютера.
 *
 * @param startedAt когда прогулка началась, мс — её имя: по нему она узнаётся после переподключения
 * @param dateEpochDay день, к которому прогулка относится, — по её началу
 * @param seenAt когда дорожка последний раз о ней сообщила
 * @param toldAt когда Зёрнышко рассказало о ней в ленте; `null` — ещё нет
 */
@Entity(tableName = "walk", indices = [Index("dateEpochDay")])
data class WalkEntity(
    @PrimaryKey val startedAt: Long,
    val dateEpochDay: Long,
    val seenAt: Long,
    val distanceM: Int,
    val kcal: Int,
    val elapsedS: Int,
    val steps: Double,
    val topSpeedKmh: Double,
    val toldAt: Long? = null,
)

@Dao
interface WalkDao {

    @Upsert
    suspend fun upsert(walk: WalkEntity)

    /** Последняя прогулка — к ней дорожка может вернуться после обрыва связи. */
    @Query("SELECT * FROM walk ORDER BY startedAt DESC LIMIT 1")
    suspend fun last(): WalkEntity?

    @Query("SELECT * FROM walk ORDER BY startedAt DESC LIMIT 1")
    fun observeLast(): Flow<WalkEntity?>

    @Query("SELECT * FROM walk WHERE dateEpochDay BETWEEN :from AND :to ORDER BY startedAt")
    fun observeDays(from: Long, to: Long): Flow<List<WalkEntity>>

    @Query("SELECT * FROM walk WHERE dateEpochDay = :dateEpochDay ORDER BY startedAt")
    suspend fun day(dateEpochDay: Long): List<WalkEntity>

    @Query("UPDATE walk SET toldAt = :at WHERE startedAt = :startedAt")
    suspend fun markTold(startedAt: Long, at: Long)
}
