package n7.kcalai.repositories

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import n7.kcalai.database.WalkDao
import n7.kcalai.model.TreadmillSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/** Пакеты дорожки, подключённой к телефону: живые числа — странице, прогулка — в базу. */
class TreadmillFeedTest {

    private val dao: WalkDao = MemoryWalkDao()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-11-25T18:30:00Z")
    }
    private val feed = TreadmillFeed(WalkRepository(dao, TimeZone.UTC), clock)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `пакет дорожки — живые числа и прогулка в базе`() = runBlocking {
        feed.packet(hex("8625c8000000ec0900a000000000001a12000000"))

        assertEquals(TreadmillSample(2.0, 2540, 160, 4634), feed.live.value)
        assertEquals(2540, dao.last()?.distanceM)
    }

    @Test
    fun `битый пакет ничего не меняет`() = runBlocking {
        feed.packet(hex("8625"))

        assertNull(feed.live.value)
        assertNull(dao.last())
    }

    @Test
    fun `дорожка отключилась — живых чисел нет, прогулка осталась`() = runBlocking {
        feed.packet(hex("8625c8000000ec0900a000000000001a12000000"))
        feed.gone()

        assertNull(feed.live.first())
        assertEquals(2540, dao.last()?.distanceM)
    }
}
