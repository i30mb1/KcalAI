package n7.kcalai.fooddb

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile

/**
 * Вложенный в бандл справочник — общему [SeedInstaller].
 *
 * Платформенного здесь ровно столько же, сколько на Android: где лежат байты
 * и где папка данных. Правила установки — отметки, атомарная замена, продвижение
 * докачанного — общие.
 *
 * @return путь к готовому к открытию seed.db
 */
fun installSeed(): Path = SeedInstaller.install(
    dir = Path(seedDirectory()),
    expectedDigest = bundleText("seed.db", "sha256"),
    openAsset = { SystemFileSystem.source(Path(bundlePath("seed.db", null))).buffered() },
)

/** Справочник живёт рядом с дневником — в Application Support, не на глазах у человека. */
@OptIn(ExperimentalForeignApi::class)
fun seedDirectory(): String {
    val support = NSSearchPathForDirectoriesInDomains(
        directory = NSApplicationSupportDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String ?: error("нет папки Application Support")
    NSFileManager.defaultManager.createDirectoryAtPath(support, true, null, null)
    return support
}

private fun bundlePath(name: String, extension: String?): String =
    requireNotNull(NSBundle.mainBundle.pathForResource(name, extension)) {
        "в бандле нет $name${extension?.let { ".$it" }.orEmpty()}"
    }

@OptIn(ExperimentalForeignApi::class)
private fun bundleText(name: String, extension: String): String =
    requireNotNull(
        NSString.stringWithContentsOfFile(bundlePath(name, extension), NSUTF8StringEncoding, null),
    ) { "не читается $name.$extension из бандла" }
