package n7.kcalai.fooddb

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Докачанный справочник встаёт на место только при следующем запуске — под
 * открытым соединением базу не подменяют.
 *
 * Проверяется на обеих платформах: правила обновления справочника у Android
 * и iOS обязаны совпадать, иначе на одной из них дневник останется со старой базой.
 */
class SeedInstallerTest {

    private val dir = Path(SystemTemporaryDirectory, "seed-test-${counter++}")
        .also { SystemFileSystem.createDirectories(it) }

    @AfterTest
    fun cleanup() {
        SystemFileSystem.list(dir).forEach(SystemFileSystem::delete)
        SystemFileSystem.delete(dir)
    }

    private fun file(name: String, content: String) = Path(dir, name).also { path ->
        SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
    }

    private fun Path.text() = SystemFileSystem.source(this).buffered().use { it.readString() }

    private fun exists(name: String) = SystemFileSystem.exists(Path(dir, name))

    @Test
    fun `sha256 файла в hex`() {
        assertEquals(TEST_SHA, SeedInstaller.sha256(file("x", "test")))
    }

    @Test
    fun `подготовленный файл с верным хешем становится next`() {
        val downloaded = file("dl.tmp", "test")

        assertTrue(SeedInstaller.stage(dir, downloaded, TEST_SHA))
        assertEquals(TEST_SHA, SeedInstaller.stagedDigest(dir))
        assertFalse(exists("dl.tmp"))
    }

    @Test
    fun `битая закачка не становится next`() {
        val downloaded = file("dl.tmp", "обрезано")

        assertFalse(SeedInstaller.stage(dir, downloaded, TEST_SHA))
        assertNull(SeedInstaller.stagedDigest(dir))
        assertFalse(exists("dl.tmp"))
    }

    @Test
    fun `next продвигается в seed и пишет stamp`() {
        file("seed.db", "старая")
        file("seed.db.stamp", "old")
        SeedInstaller.stage(dir, file("dl.tmp", "test"), TEST_SHA)

        assertTrue(SeedInstaller.promoteNext(dir))
        assertEquals("test", Path(dir, "seed.db").text())
        assertEquals(TEST_SHA, SeedInstaller.installedDigest(dir))
        assertNull(SeedInstaller.stagedDigest(dir))
    }

    @Test
    fun `next с испорченным содержимым выбрасывается seed остаётся`() {
        file("seed.db", "старая")
        file("seed.db.next", "испорчено")
        file("seed.db.next.sha256", TEST_SHA)

        assertFalse(SeedInstaller.promoteNext(dir))
        assertEquals("старая", Path(dir, "seed.db").text())
        assertFalse(exists("seed.db.next"))
    }

    @Test
    fun `без next продвигать нечего`() {
        assertFalse(SeedInstaller.promoteNext(dir))
    }

    private companion object {
        /** sha256("test") */
        const val TEST_SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

        /** Каталог у каждого теста свой, а тянуть ради этого часы — перебор. */
        var counter = 0
    }
}
