package n7.kcalai.fooddb

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class EmbeddingBlobTest {

    @Test
    fun `вектор хранится как float32 little-endian`() {
        // 1.0f = 0x3F800000, -2.5f = 0xC0200000, младший байт первым.
        val blob = byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F, 0x00, 0x00, 0x20, 0xC0.toByte())

        assertContentEquals(floatArrayOf(1.0f, -2.5f), decodeEmbedding(blob))
    }

    @Test
    fun `записанный вектор читается обратно тем же`() {
        val vector = floatArrayOf(1.0f, -2.5f, 0.125f)

        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F, 0x00, 0x00, 0x20, 0xC0.toByte(), 0x00, 0x00, 0x00, 0x3E),
            encodeEmbedding(vector),
        )
        assertContentEquals(vector, decodeEmbedding(encodeEmbedding(vector)))
    }

    @Test
    fun `обрезанный блоб — ошибка сборки базы — не вектор`() {
        assertFailsWith<IllegalArgumentException> { decodeEmbedding(byteArrayOf(0, 0, 0)) }
    }
}
