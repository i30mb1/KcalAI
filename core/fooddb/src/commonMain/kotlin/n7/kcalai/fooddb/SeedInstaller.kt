package n7.kcalai.fooddb

import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Раскладывает `seed.db` из сборки в папку данных и подхватывает докачанный.
 *
 * SQLite не умеет открывать файл внутри APK или бандла, поэтому справочник обязан
 * оказаться на файловой системе.
 *
 * Два источника, и у каждого своя отметка. `seed.db.asset` — хеш того, из чего
 * ставили в прошлый раз: изменилось вложенное в сборку (обновилось приложение) —
 * ставим из него, а всё докачанное выбрасываем: релиз новее. `seed.db.stamp` —
 * хеш того, что стоит сейчас; по нему обновление решает, есть ли на сервере
 * что-то новее.
 *
 * Версия определяется хешем, а не размером файла: SQLite выравнивает базу по
 * страницам, и правка текста внутри строки его не двигает — приложение осталось
 * бы на старой копии, причём молча.
 *
 * Докачанный файл живёт в `seed.db.next` до следующего запуска: под открытым
 * соединением базу не подменяют.
 *
 * Всё здесь — общий код: правила обновления справочника у Android и iOS обязаны
 * совпадать. Платформа даёт только содержимое вложенного в сборку файла, см. [install].
 */
object SeedInstaller {

    private const val DB_NAME = "seed.db"
    private const val STAMP_NAME = "seed.db.stamp"
    private const val ASSET_STAMP_NAME = "seed.db.asset"
    private const val NEXT_NAME = "seed.db.next"
    private const val NEXT_DIGEST_NAME = "seed.db.next.sha256"

    /**
     * @param dir папка данных приложения: `filesDir` на Android, Application Support на iOS
     * @param expectedDigest хеш вложенного в сборку справочника — рядом с ним и лежит
     * @param openAsset содержимое вложенного в сборку `seed.db`. Зовётся, только когда
     *        ставить и правда надо: читать 6 МБ на каждом запуске незачем.
     * @return путь к готовому к открытию seed.db
     */
    fun install(dir: Path, expectedDigest: String, openAsset: () -> Source): Path {
        val target = Path(dir, DB_NAME)
        val assetStamp = Path(dir, ASSET_STAMP_NAME)
        val expected = expectedDigest.trim()

        if (SystemFileSystem.exists(target) && readText(assetStamp)?.trim() == expected) {
            promoteNext(dir)
            return target
        }

        // Пишем во временный файл и переименовываем: прерванное копирование не должно
        // оставить обрезанную базу, которую следующий запуск примет за целую.
        val tmp = Path(dir, "$DB_NAME.tmp")
        openAsset().use { source ->
            SystemFileSystem.sink(tmp).buffered().use { sink -> source.transferTo(sink) }
        }
        SystemFileSystem.atomicMove(tmp, target)
        SystemFileSystem.delete(Path(dir, NEXT_NAME), mustExist = false)
        SystemFileSystem.delete(Path(dir, NEXT_DIGEST_NAME), mustExist = false)

        // Отметки пишутся последними: упади копирование — на следующем запуске
        // несовпадение отметки заставит повторить установку.
        writeText(Path(dir, STAMP_NAME), expected)
        writeText(assetStamp, expected)

        return target
    }

    /** Хеш установленного справочника — с ним обновление сравнивает манифест сервера. */
    fun installedDigest(dir: Path): String? = readText(Path(dir, STAMP_NAME))?.trim()

    /** Хеш уже докачанного, но ещё не вставшего файла. `null` — ничего не ждёт. */
    fun stagedDigest(dir: Path): String? =
        if (SystemFileSystem.exists(Path(dir, NEXT_NAME))) readText(Path(dir, NEXT_DIGEST_NAME))?.trim() else null

    /**
     * Скачанный файл -> `seed.db.next`, если хеш сошёлся. Не сошёлся — файл удаляется:
     * обрезанная закачка не должна дожидаться следующего запуска.
     */
    fun stage(dir: Path, downloaded: Path, expectedSha256: String): Boolean {
        val actual = sha256(downloaded)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            SystemFileSystem.delete(downloaded, mustExist = false)
            return false
        }
        val next = Path(dir, NEXT_NAME)
        SystemFileSystem.delete(next, mustExist = false)
        SystemFileSystem.atomicMove(downloaded, next)
        writeText(Path(dir, NEXT_DIGEST_NAME), actual)
        return true
    }

    /** `seed.db.next` -> `seed.db`, если содержимое цело. Иначе `.next` выбрасывается. */
    fun promoteNext(dir: Path): Boolean {
        val next = Path(dir, NEXT_NAME)
        val digestFile = Path(dir, NEXT_DIGEST_NAME)
        if (!SystemFileSystem.exists(next)) {
            SystemFileSystem.delete(digestFile, mustExist = false)
            return false
        }
        val expected = readText(digestFile)?.trim()
        if (expected == null || !sha256(next).equals(expected, ignoreCase = true)) {
            SystemFileSystem.delete(next, mustExist = false)
            SystemFileSystem.delete(digestFile, mustExist = false)
            return false
        }
        SystemFileSystem.atomicMove(next, Path(dir, DB_NAME))
        SystemFileSystem.delete(digestFile, mustExist = false)
        writeText(Path(dir, STAMP_NAME), expected)
        return true
    }

    /** Хеш файла потоком: справочник в 6 МБ не читается в память целиком. */
    fun sha256(file: Path): String {
        val digest = Sha256()
        val chunk = ByteArray(CHUNK_BYTES)
        SystemFileSystem.source(file).buffered().use { source ->
            while (true) {
                val read = source.readAtMostTo(chunk, 0, CHUNK_BYTES)
                if (read <= 0) break
                digest.update(chunk, read)
            }
        }
        return digest.hex()
    }

    private fun readText(path: Path): String? =
        if (SystemFileSystem.exists(path)) {
            SystemFileSystem.source(path).buffered().use { it.readString() }
        } else {
            null
        }

    private fun writeText(path: Path, text: String) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }

    private const val CHUNK_BYTES = 64 * 1024
}
