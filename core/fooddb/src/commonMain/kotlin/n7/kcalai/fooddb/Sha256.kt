package n7.kcalai.fooddb

/**
 * SHA-256 потоком: справочник в 6 МБ не читается в память ради одного хеша.
 *
 * Своя реализация, потому что общего пути к системной нет: `MessageDigest` живёт
 * только на Android, а CryptoKit доступен только из Swift. Алгоритм описан
 * стандартом целиком и не меняется, а правильность держат векторы из него же —
 * см. `Sha256Test`.
 */
internal class Sha256 {

    private val state = INITIAL.copyOf()
    private val block = ByteArray(BLOCK_BYTES)
    private val schedule = IntArray(64)
    private var blockFill = 0
    private var totalBytes = 0L

    fun update(bytes: ByteArray, length: Int = bytes.size) {
        totalBytes += length
        var offset = 0
        while (offset < length) {
            val taken = minOf(BLOCK_BYTES - blockFill, length - offset)
            bytes.copyInto(block, blockFill, offset, offset + taken)
            blockFill += taken
            offset += taken
            if (blockFill == BLOCK_BYTES) {
                compress(block)
                blockFill = 0
            }
        }
    }

    /** Хеш в том же hex в нижнем регистре, в каком его присылает сервер. */
    fun hex(): String {
        // Дополнение по стандарту: один бит, нули и длина в битах последними восемью байтами.
        val lengthBits = totalBytes * 8
        update(byteArrayOf(0x80.toByte()))
        val zeros = (BLOCK_BYTES - LENGTH_BYTES - blockFill).let { if (it < 0) it + BLOCK_BYTES else it }
        if (zeros > 0) update(ByteArray(zeros))
        update(ByteArray(LENGTH_BYTES) { i -> (lengthBits ushr (56 - 8 * i)).toByte() })

        return buildString(64) {
            for (word in state) {
                for (shift in 24 downTo 0 step 8) {
                    val byte = (word ushr shift) and 0xFF
                    append(HEX[byte ushr 4])
                    append(HEX[byte and 0xF])
                }
            }
        }
    }

    private fun compress(chunk: ByteArray) {
        for (i in 0 until 16) {
            val at = i * 4
            schedule[i] = (chunk[at].toInt() and 0xFF shl 24) or
                (chunk[at + 1].toInt() and 0xFF shl 16) or
                (chunk[at + 2].toInt() and 0xFF shl 8) or
                (chunk[at + 3].toInt() and 0xFF)
        }
        for (i in 16 until 64) {
            val a = schedule[i - 15]
            val b = schedule[i - 2]
            val s0 = a.rotateRight(7) xor a.rotateRight(18) xor (a ushr 3)
            val s1 = b.rotateRight(17) xor b.rotateRight(19) xor (b ushr 10)
            schedule[i] = schedule[i - 16] + s0 + schedule[i - 7] + s1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choose = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + choose + ROUND_CONSTANTS[i] + schedule[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + majority

            h = g; g = f; f = e; e = d + temp1
            d = c; c = b; b = a; a = temp1 + temp2
        }

        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    private companion object {
        const val BLOCK_BYTES = 64
        const val LENGTH_BYTES = 8
        const val HEX = "0123456789abcdef"

        /** Дробные части корней из первых восьми простых — как велит стандарт. */
        val INITIAL = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )

        /** Дробные части кубических корней из первых 64 простых. */
        val ROUND_CONSTANTS = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
            0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
            0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
            0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
            0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
        )
    }
}
