package n7.kcalai.feature.diary

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import n7.kcalai.database.Choice
import n7.kcalai.database.WebClientDao
import n7.kcalai.database.WebClientEntity

/** Та же семантика, что у SQL в [WebClientDao]: ответ — один раз, токен — свой у каждого. */
internal class FakeWebClientDao : WebClientDao {

    private val rows = MutableStateFlow<List<WebClientEntity>>(emptyList())

    fun all(): List<WebClientEntity> = rows.value

    override suspend fun insert(client: WebClientEntity): Long {
        check(rows.value.none { it.token == client.token }) { "токен уже есть" }
        val id = (rows.value.maxOfOrNull { it.id } ?: 0) + 1
        rows.update { it + client.copy(id = id) }
        return id
    }

    override fun observe(token: String): Flow<WebClientEntity?> = rows.map { list -> list.find { it.token == token } }

    override suspend fun find(token: String): WebClientEntity? = rows.value.find { it.token == token }

    override fun observeDay(dateEpochDay: Long): Flow<List<WebClientEntity>> =
        rows.map { list -> list.filter { it.dateEpochDay == dateEpochDay } }

    override suspend fun hide(id: Long, at: Long) {
        rows.update { list -> list.map { if (it.id == id && it.choice != null) it.copy(hiddenAt = at) else it } }
    }

    override suspend fun answer(id: Long, choice: Choice): Int {
        val row = rows.value.find { it.id == id && it.choice == null } ?: return 0
        rows.update { list -> list.map { if (it.id == row.id) it.copy(choice = choice) else it } }
        return 1
    }

    override suspend fun leave(token: String, now: Long) {
        rows.update { list -> list.map { if (it.token == token && it.leftAt == null) it.copy(leftAt = now) else it } }
    }

    override suspend fun revoke(id: Long, now: Long) {
        rows.update { list -> list.map { if (it.id == id && it.leftAt == null) it.copy(leftAt = now) else it } }
    }

    override fun observeAdmitted(): Flow<List<WebClientEntity>> =
        rows.map { list -> list.filter { it.choice == Choice.YES && it.leftAt == null }.sortedBy { it.id } }

    override suspend fun waitingCount(dateEpochDay: Long): Int =
        rows.value.count { it.choice == null && it.dateEpochDay == dateEpochDay }
}
