package n7.kcalai.repositories

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import n7.kcalai.database.WalkDao
import n7.kcalai.database.WalkEntity
import n7.kcalai.model.TreadmillSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Пакеты дорожки складываются в прогулки дня, неделя шагов — всегда из семи дней. */
class WalkRepositoryTest {

    private val dao = MemoryWalkDao()
    private val zone = TimeZone.UTC
    private val repository = WalkRepository(dao, zone)

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime(2026, 11, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    private val today = kotlinx.datetime.LocalDate(2026, 11, 25).toEpochDays()

    @Test
    fun `пакеты одной прогулки — одна прогулка дня с последними счётчиками`() = runBlocking {
        repository.record(TreadmillSample(3.0, 100, 5, 120), at(25, 18, 2))
        repository.record(TreadmillSample(4.0, 600, 30, 600), at(25, 18, 10))

        val walks = repository.observeDay(today).first()

        assertEquals(1, walks.size)
        assertEquals(600, walks.single().distanceM)
        assertEquals(at(25, 18, 0), walks.single().startedAt)
    }

    @Test
    fun `прогулка относится к дню, в который началась`() = runBlocking {
        repository.record(TreadmillSample(3.0, 2000, 80, 1800), at(26, 0, 10))

        assertEquals(1, repository.observeDay(today).first().size)
    }

    @Test
    fun `неделя шагов — семь дней, пустой день ноль`() = runBlocking {
        repository.record(TreadmillSample(3.0, 1000, 40, 900), at(22, 9))
        repository.record(TreadmillSample(3.0, 1000, 40, 900), at(25, 9))

        val week = repository.observeWeekSteps(today).first()

        assertEquals((today - 6..today).toList(), week.map { it.first })
        assertEquals(listOf(0, 0, 0, 1, 0, 0, 1), week.map { if (it.second > 0) 1 else 0 })
    }

    @Test
    fun `рассказанная прогулка остаётся рассказанной, пока идёт дальше`() = runBlocking {
        repository.record(TreadmillSample(3.0, 1000, 40, 900), at(25, 9))
        val walk = repository.observeLast().first()!!
        repository.markTold(walk, at(25, 9, 20))

        repository.record(TreadmillSample(3.0, 1100, 44, 900 + 21 * 60), at(25, 9, 21))

        assertEquals(at(25, 9, 20), dao.rows.value.single().toldAt)
    }

    @Test
    fun `дорожка стоит выключенной — прогулок нет`() = runBlocking {
        repository.record(TreadmillSample(0.0, 0, 0, 0), at(25, 9))

        assertNull(repository.observeLast().first())
    }
}

internal class MemoryWalkDao : WalkDao {
    val rows = MutableStateFlow<List<WalkEntity>>(emptyList())

    override suspend fun upsert(walk: WalkEntity) {
        rows.value = (rows.value.filter { it.startedAt != walk.startedAt } + walk).sortedBy { it.startedAt }
    }

    override suspend fun last(): WalkEntity? = rows.value.lastOrNull()

    override fun observeLast(): Flow<WalkEntity?> = rows.map { it.lastOrNull() }

    override fun observeDays(from: Long, to: Long): Flow<List<WalkEntity>> = rows.map { list -> list.filter { it.dateEpochDay in from..to } }

    override suspend fun day(dateEpochDay: Long): List<WalkEntity> = rows.value.filter { it.dateEpochDay == dateEpochDay }

    override suspend fun markTold(startedAt: Long, at: Long) {
        rows.value = rows.value.map { if (it.startedAt == startedAt) it.copy(toldAt = at) else it }
    }
}
