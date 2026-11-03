package n7.kcalai.fooddb

/**
 * Отпечаток продукта из `generic_embedding.vec`: float32 little-endian подряд.
 *
 * Пишет их `tools/embeddb` на сборке справочника. Длина не хранится отдельно —
 * она равна длине блоба, делённой на четыре.
 */
fun decodeEmbedding(blob: ByteArray): FloatArray {
    require(blob.size % Float.SIZE_BYTES == 0) { "отпечаток в ${blob.size} байт — не float32" }
    return FloatArray(blob.size / Float.SIZE_BYTES) { i ->
        val o = i * Float.SIZE_BYTES
        val bits = (blob[o].toInt() and 0xFF) or
            ((blob[o + 1].toInt() and 0xFF) shl 8) or
            ((blob[o + 2].toInt() and 0xFF) shl 16) or
            ((blob[o + 3].toInt() and 0xFF) shl 24)
        Float.fromBits(bits)
    }
}
