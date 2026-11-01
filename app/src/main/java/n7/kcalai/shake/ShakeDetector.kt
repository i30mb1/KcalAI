package n7.kcalai.shake

import kotlin.math.sqrt

/**
 * Тряска телефона по показаниям акселерометра.
 *
 * Рывок — ускорение сверх силы тяжести больше [thresholdMs2]. Тряска — [jolts]
 * рывков за [windowMs]: один рывок бывает, когда телефон кладут на стол или
 * достают из кармана, три подряд за полсекунды — только когда трясут. После
 * тряски — [cooldownMs] тишины: трясут долго, а голова кружится раз.
 */
class ShakeDetector(
    private val thresholdMs2: Float = 13f,
    private val jolts: Int = 3,
    private val windowMs: Long = 600,
    private val cooldownMs: Long = 1_500,
) {
    private val times = ArrayDeque<Long>()
    private var quietUntil = Long.MIN_VALUE

    /** @return `true` — в этот момент телефон тряхнули */
    fun onSample(x: Float, y: Float, z: Float, atMs: Long): Boolean {
        if (atMs < quietUntil) return false
        val force = sqrt(x * x + y * y + z * z) - GRAVITY
        if (force < thresholdMs2) return false

        times.addLast(atMs)
        while (times.first() < atMs - windowMs) times.removeFirst()
        if (times.size < jolts) return false

        times.clear()
        quietUntil = atMs + cooldownMs
        return true
    }

    private companion object {
        const val GRAVITY = 9.81f
    }
}
