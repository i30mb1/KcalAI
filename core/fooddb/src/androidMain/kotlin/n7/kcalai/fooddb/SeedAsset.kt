package n7.kcalai.fooddb

import android.content.Context
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.files.Path

/**
 * Вложенный в APK справочник — общему [SeedInstaller].
 *
 * Всё, что осталось платформенного от установки: откуда взять байты и где папка
 * данных. Сами правила — отметки, атомарная замена, продвижение докачанного —
 * общие и проверяются на обеих платформах.
 *
 * @return путь к готовому к открытию seed.db
 */
fun installSeed(context: Context): Path {
    val assets = context.assets
    return SeedInstaller.install(
        dir = Path(context.filesDir.absolutePath),
        expectedDigest = assets.open(ASSET_DIGEST).use { it.readBytes().decodeToString() },
        openAsset = { assets.open(ASSET_NAME).asSource().buffered() },
    )
}

private const val ASSET_NAME = "seed.db"
private const val ASSET_DIGEST = "seed.db.sha256"
