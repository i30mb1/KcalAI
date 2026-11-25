package n7.kcalai.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Что сосед предложил записать.
 *
 * До подтверждения в дневник не попадает ничего: предложение живёт здесь и стоит
 * сообщением в ленте. Иначе каждому, кто читает `diary_entry` — шести моделям
 * персонализации, графику недели, фактам персонажей, — пришлось бы объяснять,
 * что часть записей ненастоящая.
 *
 * Снимок, а не ссылка: числа приехали с чужого телефона, и там они уже были
 * снимком — ссылка на строку чужой базы ничего не значит. Из ссылок переносим
 * только [gtin]: код товара глобален.
 *
 * Журнал, а не проекция — как очередь вкладов: отвеченное не удаляется, лента
 * переписка, и из неё ничего не пропадает.
 *
 * @param fromName как назвался отправитель. Подпись, а не удостоверение.
 * @param choice `null` — ждёт ответа, и кнопки живые
 */
@Entity(tableName = "peer_offer", indices = [Index("dateEpochDay"), Index("createdAt")])
data class PeerOfferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromName: String,
    val displayName: String,
    val grams: Int,
    val kcal100: Int,
    val prot100: Int,
    val fat100: Int,
    val carb100: Int,
    /** `null` — на пачке клетчатки не было. Это не ноль: ноль занизил бы недобор. */
    val fiber100: Int? = null,
    val gtin: String? = null,
    val dateEpochDay: Long,
    val createdAt: Long,
    val choice: Choice? = null,
    /** Когда отвеченное предложение убрали из переписки корзинкой. */
    val hiddenAt: Long? = null,
)

@Dao
interface PeerOfferDao {

    @Insert
    suspend fun insert(offer: PeerOfferEntity): Long

    /** Предложения одного дня — в его переписку. */
    @Query("SELECT * FROM peer_offer WHERE dateEpochDay = :dateEpochDay ORDER BY createdAt, id")
    fun observeDay(dateEpochDay: Long): Flow<List<PeerOfferEntity>>

    @Query("SELECT * FROM peer_offer WHERE id = :id")
    suspend fun findById(id: Long): PeerOfferEntity?

    /**
     * Ответ — по id, а не по теме: предложений бывает несколько разных сразу,
     * и ответ на гречку не должен накрывать творог.
     *
     * @return сколько строк получили ответ; ноль — ответ уже дан, второй не принимается
     */
    @Query("UPDATE peer_offer SET choice = :choice WHERE id = :id AND choice IS NULL")
    suspend fun answer(id: Long, choice: Choice): Int

    /** Сколько предложений ждёт ответа — по ним считается предел на одного соседа. */
    /** Убрать отвеченное предложение из переписки корзинкой. */
    @Query("UPDATE peer_offer SET hiddenAt = :at WHERE id = :id AND choice IS NOT NULL")
    suspend fun hide(id: Long, at: Long)

    @Query("SELECT count(*) FROM peer_offer WHERE choice IS NULL")
    suspend fun waitingCount(): Int
}
