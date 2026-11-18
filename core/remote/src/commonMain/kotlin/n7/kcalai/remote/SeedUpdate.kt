package n7.kcalai.remote

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import n7.kcalai.fooddb.SeedInstaller
import n7.kcalai.model.Log

/** Чем кончилась попытка обновить справочник. */
enum class SeedUpdate {
    /** Сервер молчит или его нет — попробуем при следующем пробуждении. */
    UNAVAILABLE,

    /** Такой справочник уже стоит или ждёт своей очереди. */
    UP_TO_DATE,

    /** Докачан и встанет при следующем запуске. */
    STAGED,

    /** Закачка не сошлась с хешем — выброшена. */
    BROKEN,
}

/**
 * Докачать свежий справочник — одним кодом на Android и iOS; платформа только будит.
 *
 * Скачанное не встаёт на место сразу: `FoodDb` держит соединение с текущим файлом,
 * и подменять его под ним нельзя. Файл ложится в `seed.db.next`, а при следующем запуске
 * `SeedInstaller.install` его продвигает. Повторов нет: нет сети или сервера — попробуем при
 * следующем пробуждении.
 *
 * @param dir каталог справочника на устройстве
 */
suspend fun updateSeed(dir: Path, source: SeedSource): SeedUpdate {
    val manifest = source.seedManifest() ?: return SeedUpdate.UNAVAILABLE
    if (manifest.sha256.equals(SeedInstaller.installedDigest(dir), ignoreCase = true) ||
        manifest.sha256.equals(SeedInstaller.stagedDigest(dir), ignoreCase = true)
    ) {
        return SeedUpdate.UP_TO_DATE
    }

    val tmp = Path(dir, "seed.db.download")
    if (!source.downloadSeed(manifest, tmp.toString())) {
        SystemFileSystem.delete(tmp, mustExist = false)
        return SeedUpdate.UNAVAILABLE
    }
    val ready = SeedInstaller.stage(dir, tmp, manifest.sha256)
    Log.i(TAG, if (ready) "справочник v${manifest.version} докачан, встанет при следующем запуске" else "закачка справочника битая, выброшена")
    return if (ready) SeedUpdate.STAGED else SeedUpdate.BROKEN
}

private const val TAG = "SeedUpdate"
