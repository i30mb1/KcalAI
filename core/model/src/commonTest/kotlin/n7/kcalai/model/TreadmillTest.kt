package n7.kcalai.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreadmillTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val t0 = 1_795_000_000_000L

    @Test
    fun `разбирает настоящий пакет Umay-TM — скорость, дистанция, ккал, время`() {
        val d = parseTreadmillData(hex("8625c8000000ec0900a000000000001a12000000"))
        assertEquals(TreadmillSample(speedKmh = 2.0, distanceM = 2540, kcal = 160, elapsedS = 4634), d)
    }

    @Test
    fun `обрезанный пакет не роняет разбор`() {
        assertNull(parseTreadmillData(hex("8625c800")))
        assertNull(parseTreadmillData(ByteArray(1)))
    }

    @Test
    fun `первый пакет заводит прогулку, начавшуюся elapsed секунд назад`() {
        val w = track(null, TreadmillSample(2.0, 2540, 160, 4634), t0)!!
        assertEquals(t0 - 4634_000, w.startedAt)
        assertEquals(2540, w.distanceM)
        assertTrue(w.steps > 0)
    }

    @Test
    fun `следующие пакеты обновляют ту же прогулку`() {
        val first = track(null, TreadmillSample(2.0, 2540, 160, 4634), t0)!!
        val next = track(first, TreadmillSample(2.0, 2550, 161, 4645), t0 + 11_000)!!
        assertEquals(first.startedAt, next.startedAt)
        assertEquals(2550, next.distanceM)
        assertEquals(161, next.kcal)
        assertEquals(t0 + 11_000, next.seenAt)
    }

    @Test
    fun `шаги растут вместе с дистанцией, а не пересчитываются заново`() {
        val first = track(null, TreadmillSample(2.0, 1000, 50, 1800), t0)!!
        val next = track(first, TreadmillSample(5.0, 1100, 55, 1872), t0 + 72_000)!!
        assertTrue(abs(next.steps - first.steps - 100 / strideMeters(5.0)) < 1)
    }

    @Test
    fun `быстрейший темп прогулки запоминается`() {
        var w = track(null, TreadmillSample(3.0, 100, 5, 120), t0)!!
        w = track(w, TreadmillSample(5.5, 200, 10, 190), t0 + 70_000)!!
        w = track(w, TreadmillSample(2.0, 210, 10, 210), t0 + 90_000)!!
        assertEquals(5.5, w.topSpeedKmh)
    }

    @Test
    fun `счётчики сбросились — это новая прогулка`() {
        val first = track(null, TreadmillSample(3.0, 3000, 150, 3600), t0)!!
        val next = track(first, TreadmillSample(3.0, 20, 1, 25), t0 + 30_000)!!
        assertTrue(next.startedAt != first.startedAt)
        assertEquals(20, next.distanceM)
    }

    @Test
    fun `переподключились к той же прогулке через 20 минут — не считаем её дважды`() {
        val first = track(null, TreadmillSample(3.0, 1000, 50, 1200), t0)!!
        val next = track(first, TreadmillSample(3.0, 2000, 100, 2400), t0 + 1_200_000)!!
        assertEquals(first.startedAt, next.startedAt)
        assertEquals(2000, next.distanceM)
    }

    @Test
    fun `дорожку выключали и включали, пока нас не было — новая прогулка`() {
        val first = track(null, TreadmillSample(3.0, 500, 25, 600), t0)!!
        val next = track(first, TreadmillSample(3.0, 900, 45, 1000), t0 + 3_600_000)!!
        assertTrue(next.startedAt != first.startedAt)
    }

    @Test
    fun `дорожка включена, но не запущена — прогулки нет`() {
        assertNull(track(null, TreadmillSample(0.0, 0, 0, 0), t0))
    }

    @Test
    fun `итог дня складывает прогулки этого дня`() {
        val a = Walk(startedAt = 1_000, seenAt = 61_000, distanceM = 1000, kcal = 40, elapsedS = 600, steps = 1300.0, topSpeedKmh = 4.0)
        val b = a.copy(startedAt = 100_000, seenAt = 200_000, distanceM = 500, kcal = 20, elapsedS = 300, steps = 700.0)
        val t = listOf(a, b).totals()
        assertEquals(WalkTotals(steps = 2000, distanceM = 1500, elapsedS = 900, kcal = 60, walks = 2), t)
    }

    @Test
    fun `длина шага растёт со скоростью`() {
        assertTrue(abs(strideMeters(2.0) - 0.578) < 0.01)
        assertTrue(strideMeters(6.0) > strideMeters(3.0))
    }

    @Test
    fun `прогулка закончилась, когда дорожка давно молчит или стоит`() {
        val w = track(null, TreadmillSample(3.0, 500, 25, 600), t0)!!
        assertEquals(false, w.isOver(t0 + 30_000))
        assertEquals(true, w.isOver(t0 + WALK_OVER_MS + 1))
    }
}
