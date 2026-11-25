package n7.kcalai.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Браузер, который просится в дневник со страницы на телефоне.
 *
 * Пускает человек — вопросом Яйца в ленте, и до ответа браузер не видит ничего.
 * Просьба — сообщение переписки, поэтому строка не удаляется ни после ответа,
 * ни после «Выйти»: ушедший браузер помечается [leftAt].
 *
 * @param token чем браузер называется: он хранит его у себя и предъявляет при каждом
 *        подключении. Случайный и длинный — угадать его соседу по сети нельзя.
 * @param code короткий код, который браузер показывает у себя, а Яйцо — в вопросе:
 *        совпали — значит, пускаешь тот браузер, что перед тобой
 * @param label чем браузер назвался — «Chrome на Windows». Подпись, а не удостоверение.
 * @param choice `null` — ждёт ответа, и кнопки живые
 * @param leftAt когда браузер вышел сам; `null` — не выходил
 */
@Entity(
    tableName = "web_client",
    indices = [Index(value = ["token"], unique = true), Index("dateEpochDay")],
)
data class WebClientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val token: String,
    val code: String,
    val label: String,
    val dateEpochDay: Long,
    val createdAt: Long,
    val choice: Choice? = null,
    val leftAt: Long? = null,
    /** Когда просьбу убрали из переписки корзинкой; доступ браузера это не меняет. */
    val hiddenAt: Long? = null,
)

/** Пущен и не вышел — ему можно показывать дневник. */
val WebClientEntity.admitted: Boolean get() = choice == Choice.YES && leftAt == null

@Dao
interface WebClientDao {

    @Insert
    suspend fun insert(client: WebClientEntity): Long

    @Query("SELECT * FROM web_client WHERE token = :token")
    fun observe(token: String): Flow<WebClientEntity?>

    @Query("SELECT * FROM web_client WHERE token = :token")
    suspend fun find(token: String): WebClientEntity?

    /** Просьбы одного дня — в его переписку. */
    @Query("SELECT * FROM web_client WHERE dateEpochDay = :dateEpochDay ORDER BY createdAt, id")
    fun observeDay(dateEpochDay: Long): Flow<List<WebClientEntity>>

    /** @return сколько строк получили ответ; ноль — ответ уже дан, второй не принимается */
    @Query("UPDATE web_client SET choice = :choice WHERE id = :id AND choice IS NULL")
    suspend fun answer(id: Long, choice: Choice): Int

    @Query("UPDATE web_client SET leftAt = :now WHERE token = :token AND leftAt IS NULL")
    suspend fun leave(token: String, now: Long)

    /** Убрать отвеченную просьбу из переписки корзинкой — доступ браузера остаётся каким был. */
    @Query("UPDATE web_client SET hiddenAt = :at WHERE id = :id AND choice IS NOT NULL")
    suspend fun hide(id: Long, at: Long)

    /** Отозвать по id — из настроек страницы: и чужой браузер, и свой. */
    @Query("UPDATE web_client SET leftAt = :now WHERE id = :id AND leftAt IS NULL")
    suspend fun revoke(id: Long, now: Long)

    /** Пущенные и не вышедшие — список в настройках страницы. */
    @Query("SELECT * FROM web_client WHERE choice = 'YES' AND leftAt IS NULL ORDER BY id")
    fun observeAdmitted(): Flow<List<WebClientEntity>>

    /**
     * Сколько сегодняшних просьб ждёт ответа: по ним считается предел. Вчерашние не в счёт:
     * в ленте их уже не видно, ответить на них нельзя, и держать предел они не должны.
     */
    @Query("SELECT count(*) FROM web_client WHERE choice IS NULL AND dateEpochDay = :dateEpochDay")
    suspend fun waitingCount(dateEpochDay: Long): Int
}
