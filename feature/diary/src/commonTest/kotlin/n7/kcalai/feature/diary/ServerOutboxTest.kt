package n7.kcalai.feature.diary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import n7.kcalai.database.ContributionDao
import n7.kcalai.database.ContributionEntity
import n7.kcalai.remote.ContributionUploader
import n7.kcalai.remote.UploadOutcome

/**
 * Очередь отправки, которую Яйцо предлагает отправить. Общий код для Android и iOS:
 * платформа даёт только память об отказе и, у разработчика, очередь съёмок.
 */
class ServerOutboxTest {

    private val dao = FakeContributions()
    private val memory = FakeMemory()
    private var accepts = true

    private val uploader = ContributionUploader { batch -> if (accepts) batch.mapTo(HashSet()) { it.gtin } else emptySet() }

    private fun outbox(scans: ScanQueue? = null) = ServerOutbox(dao, uploader, probe = { accepts }, memory, scans)

    private fun product(gtin: String) = ContributionEntity(
        gtin = gtin, name = "Хлебцы", kcal100 = 310, prot100 = 1_100, fat100 = 200, carb100 = 5_700, servingG = 30, createdAt = 1,
    )

    @Test
    fun `в очереди продукты — съёмок без очереди съёмок нет`() = runTest {
        dao.insert(product("1"))
        dao.insert(product("2"))

        assertEquals(OutboxCount(scans = 0, products = 2), outbox().pending())
        assertEquals(OutboxCount(scans = 3, products = 2), outbox(FakeScans(3)).pending())
    }

    @Test
    fun `отправлено — очередь пуста и отказ забыт`() = runTest {
        dao.insert(product("1"))
        memory.remember(OutboxCount(0, 1))

        assertEquals(OutboxResult.Sent(scans = 0, products = 1), outbox().send())

        assertEquals(0, dao.pendingCount())
        assertNull(memory.declined())
    }

    @Test
    fun `сервер не принял ничего — недоступен, отказ помнится`() = runTest {
        dao.insert(product("1"))
        memory.remember(OutboxCount(0, 1))
        accepts = false

        assertEquals(OutboxResult.Unreachable, outbox().send())

        assertEquals(1, dao.pendingCount())
        assertEquals(OutboxCount(0, 1), memory.declined())
    }

    /** Пустая категория попыткой не считается: съёмки ушли, а продуктов не было — это успех. */
    @Test
    fun `пустая категория не делает сервер недоступным`() = runTest {
        assertEquals(OutboxResult.Sent(scans = 2, products = 0), outbox(FakeScans(2)).send())
    }

    @Test
    fun `не сейчас — запоминается`() = runTest {
        outbox().decline(OutboxCount(0, 2))

        assertEquals(OutboxCount(0, 2), outbox().declined())
    }

    private class FakeScans(private val count: Int) : ScanQueue {
        override suspend fun pending(): Int = count
        override suspend fun upload(): UploadOutcome = UploadOutcome(count, reachable = if (count > 0) true else null)
    }

    private class FakeMemory : OutboxMemory {
        private var value: OutboxCount? = null
        override suspend fun declined(): OutboxCount? = value
        override suspend fun remember(declined: OutboxCount?) {
            value = declined
        }
    }

    private class FakeContributions : ContributionDao {
        private val rows = mutableListOf<ContributionEntity>()

        override suspend fun insert(contribution: ContributionEntity): Long {
            rows += contribution.copy(id = rows.size + 1L)
            return rows.size.toLong()
        }

        override suspend fun pending(limit: Int): List<ContributionEntity> = rows.filter { it.sentAt == null }.take(limit)

        override suspend fun pendingCount(): Int = rows.count { it.sentAt == null }

        override suspend fun markSent(ids: List<Long>, sentAt: Long) {
            rows.replaceAll { if (it.id in ids) it.copy(sentAt = sentAt) else it }
        }
    }
}
