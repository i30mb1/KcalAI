package n7.kcalai.remote

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import n7.kcalai.fooddb.SeedInstaller

/**
 * Обновление справочника — общее для Android и iOS: платформа только будит,
 * а что качать, проверять и куда класть — решается здесь.
 */
class SeedUpdateTest {

    private val dir = Path(SystemTemporaryDirectory, "seed-update-${Random.nextLong().toULong()}").also {
        SystemFileSystem.createDirectories(it)
    }

    @AfterTest
    fun clean() {
        SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
        SystemFileSystem.delete(dir, mustExist = false)
    }

    private val payload = "справочник v2".encodeToByteArray()

    /** Хеш того, что сервер на самом деле отдаст. */
    private fun digestOf(bytes: ByteArray): String {
        val file = Path(dir, "probe")
        SystemFileSystem.sink(file).buffered().use { it.write(bytes) }
        return SeedInstaller.sha256(file).also { SystemFileSystem.delete(file) }
    }

    @Test
    fun `сервер молчит — ничего не трогаем`() = runTest {
        assertEquals(SeedUpdate.UNAVAILABLE, updateSeed(dir, FakeSource(manifest = null)))
    }

    @Test
    fun `свежий справочник докачан и ждёт следующего запуска`() = runTest {
        val sha = digestOf(payload)

        assertEquals(SeedUpdate.STAGED, updateSeed(dir, FakeSource(manifest(sha), payload)))

        assertEquals(sha, SeedInstaller.stagedDigest(dir))
        assertFalse(SystemFileSystem.exists(Path(dir, "seed.db.download")), "временный файл убран")
    }

    @Test
    fun `тот же справочник второй раз не качают`() = runTest {
        val sha = digestOf(payload)
        updateSeed(dir, FakeSource(manifest(sha), payload))
        val again = FakeSource(manifest(sha), payload)

        assertEquals(SeedUpdate.UP_TO_DATE, updateSeed(dir, again))
        assertEquals(0, again.downloads)
    }

    @Test
    fun `битая закачка выброшена`() = runTest {
        val sha = digestOf(payload)

        assertEquals(SeedUpdate.BROKEN, updateSeed(dir, FakeSource(manifest(sha), "не то".encodeToByteArray())))

        assertNull(SeedInstaller.stagedDigest(dir))
    }

    private fun manifest(sha: String) = SeedManifest(version = 2, sha256 = sha, size = payload.size.toLong(), url = "seed")

    private class FakeSource(private val manifest: SeedManifest?, private val bytes: ByteArray = ByteArray(0)) : SeedSource {
        var downloads = 0

        override suspend fun seedManifest(): SeedManifest? = manifest

        override suspend fun downloadSeed(manifest: SeedManifest, to: String): Boolean {
            downloads++
            SystemFileSystem.sink(Path(to)).buffered().use { it.write(bytes) }
            return true
        }
    }
}
