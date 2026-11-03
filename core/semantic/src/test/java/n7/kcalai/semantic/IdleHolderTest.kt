package n7.kcalai.semantic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleHolderTest {

    private class Resource : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private val opened = mutableListOf<Resource>()

    private fun TestScope.holder(available: Boolean = true) =
        IdleHolder(backgroundScope, IDLE) { if (available) Resource().also(opened::add) else null }

    @Test
    fun `открывается при первом обращении и дальше переиспользуется`() = runTest {
        val holder = holder()

        holder.use { }
        holder.use { }

        assertEquals(1, opened.size)
    }

    @Test
    fun `после простоя закрывается и освобождает память`() = runTest {
        val holder = holder()

        holder.use { }
        advanceTimeBy(IDLE + 1)
        runCurrent()

        assertEquals(true, opened.single().closed)
    }

    @Test
    fun `после закрытия следующее обращение открывает заново`() = runTest {
        val holder = holder()

        holder.use { }
        advanceTimeBy(IDLE + 1)
        holder.use { }

        assertEquals(2, opened.size)
    }

    @Test
    fun `обращение в окне простоя откладывает закрытие`() = runTest {
        val holder = holder()

        holder.use { }
        advanceTimeBy(IDLE - 10)
        holder.use { }
        advanceTimeBy(IDLE - 10)
        runCurrent()

        assertEquals(false, opened.single().closed)
    }

    @Test
    fun `нечего открыть — нет ни результата, ни таймера`() = runTest {
        val holder = holder(available = false)

        assertNull(holder.use { 42 })
        assertEquals(0, opened.size)
    }

    private companion object {
        const val IDLE = 120_000L
    }
}
