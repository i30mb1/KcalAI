package n7.kcalai.fooddb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Докачанный справочник встаёт на место только при следующем запуске — под
 * открытым соединением базу не подменяют. Здесь — та часть, что живёт над File
 * и не требует Context.
 */
class SeedInstallerTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "seed-test-${System.nanoTime()}").apply { mkdirs() }

    private fun file(name: String, content: String) = File(dir, name).apply { writeText(content) }

    @Test
    fun `sha256 файла в hex`() {
        assertEquals(TEST_SHA, SeedInstaller.sha256(file("x", "test")))
    }

    @Test
    fun `подготовленный файл с верным хешем становится next`() {
        val downloaded = file("dl.tmp", "test")

        assertTrue(SeedInstaller.stage(dir, downloaded, TEST_SHA))
        assertEquals(TEST_SHA, SeedInstaller.stagedDigest(dir))
        assertFalse(downloaded.exists())
    }

    @Test
    fun `битая закачка не становится next`() {
        val downloaded = file("dl.tmp", "обрезано")

        assertFalse(SeedInstaller.stage(dir, downloaded, TEST_SHA))
        assertNull(SeedInstaller.stagedDigest(dir))
        assertFalse(downloaded.exists())
    }

    @Test
    fun `next продвигается в seed и пишет stamp`() {
        file("seed.db", "старая")
        file("seed.db.stamp", "old")
        SeedInstaller.stage(dir, file("dl.tmp", "test"), TEST_SHA)

        assertTrue(SeedInstaller.promoteNext(dir))
        assertEquals("test", File(dir, "seed.db").readText())
        assertEquals(TEST_SHA, SeedInstaller.installedDigest(dir))
        assertNull(SeedInstaller.stagedDigest(dir))
    }

    @Test
    fun `next с испорченным содержимым выбрасывается, seed остаётся`() {
        file("seed.db", "старая")
        file("seed.db.next", "испорчено")
        file("seed.db.next.sha256", TEST_SHA)

        assertFalse(SeedInstaller.promoteNext(dir))
        assertEquals("старая", File(dir, "seed.db").readText())
        assertFalse(File(dir, "seed.db.next").exists())
    }

    @Test
    fun `без next продвигать нечего`() {
        assertFalse(SeedInstaller.promoteNext(dir))
    }

    private companion object {
        /** sha256("test") */
        const val TEST_SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    }
}
