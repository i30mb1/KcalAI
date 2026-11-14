package n7.kcalai.fooddb

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Контрольные суммы из самого стандарта: справочник приезжает по сети, и хеш —
 * единственное, что отличает целую базу от оборванной закачки. Своя реализация
 * тут потому, что `MessageDigest` есть только на Android, а CryptoKit — только
 * в Swift; проверяется она теми же векторами, что и любая чужая.
 */
class Sha256Test {

    @Test
    fun `векторы из стандарта`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", sha256Hex("test"))
    }

    /** 56 байт — ровно та длина, на которой дополнение не влезает в блок и переносится в следующий. */
    @Test
    fun `на границе блока дополнение переносится`() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun `длина за 64 байта считается по всем блокам`() {
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            sha256Hex("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"),
        )
    }

    private fun sha256Hex(text: String) = Sha256().apply { update(text.encodeToByteArray()) }.hex()
}
