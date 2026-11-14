package n7.kcalai.remote

import kotlinx.coroutines.test.runTest
import n7.kcalai.database.ContributionDao
import n7.kcalai.database.ContributionEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Очередь вкладов: что уехало — помечено, что сервер отверг — ждёт следующего раза.
 *
 * Расписание у платформ своё, а это — общее: разойдись они в том, что считать
 * отправленным, на одной из платформ продукты уезжали бы дважды или не уезжали вовсе.
 */
class ContributionQueueTest {

    @Test
    fun `принятое помечается отправленным`() = runTest {
        val dao = FakeDao(contribution(1, "4600000000017"), contribution(2, "4600000000024"))

        val outcome = uploadPendingContributions(dao, uploader = { items -> items.map { it.gtin }.toSet() })

        assertEquals(2, outcome.sent)
        assertEquals(true, outcome.reachable)
        assertEquals(listOf(1L, 2L), dao.sentIds)
    }

    /** Сервер не ответил — очередь цела, и расписание узнаёт, что сервер молчит. */
    @Test
    fun `молчащий сервер оставляет очередь нетронутой`() = runTest {
        val dao = FakeDao(contribution(1, "4600000000017"))

        val outcome = uploadPendingContributions(dao, uploader = { emptySet() })

        assertEquals(0, outcome.sent)
        assertEquals(false, outcome.reachable)
        assertTrue(dao.sentIds.isEmpty())
    }

    /** Часть пачки отвергнута — она остаётся в очереди, а крутиться на ней незачем. */
    @Test
    fun `отвергнутое остаётся ждать`() = runTest {
        val good = contribution(1, "4600000000017")
        val bad = contribution(2, "4600000000024")
        val dao = FakeDao(good, bad)

        val outcome = uploadPendingContributions(dao, uploader = { setOf(good.gtin) })

        assertEquals(1, outcome.sent)
        assertEquals(true, outcome.reachable)
        assertEquals(listOf(1L), dao.sentIds)
    }

    /** Слать нечего — о сервере эта попытка не говорит ничего, и будить его незачем. */
    @Test
    fun `пустая очередь ничего не говорит о сервере`() = runTest {
        val outcome = uploadPendingContributions(FakeDao(), uploader = { error("сервер не должен был понадобиться") })

        assertEquals(0, outcome.sent)
        assertNull(outcome.reachable)
    }

    private fun contribution(id: Long, gtin: String) = ContributionEntity(
        id = id,
        gtin = gtin,
        name = "Продукт $id",
        kcal100 = 100,
        prot100 = 100,
        fat100 = 100,
        carb100 = 100,
        servingG = null,
        createdAt = id,
    )

    private class FakeDao(vararg items: ContributionEntity) : ContributionDao {
        private val queue = items.toMutableList()
        val sentIds = mutableListOf<Long>()

        override suspend fun pending(limit: Int) = queue.take(limit)

        override suspend fun markSent(ids: List<Long>, sentAt: Long) {
            sentIds += ids
            queue.removeAll { it.id in ids }
        }

        override suspend fun insert(contribution: ContributionEntity) = 0L

        override suspend fun pendingCount() = queue.size
    }
}
