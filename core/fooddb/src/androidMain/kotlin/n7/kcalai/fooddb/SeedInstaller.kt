package n7.kcalai.fooddb

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Раскладывает `seed.db` из assets в `filesDir` и подхватывает докачанный.
 *
 * SQLite не умеет открывать файл внутри APK, поэтому справочник обязан оказаться
 * на файловой системе.
 *
 * Два источника, и у каждого своя отметка. `seed.db.asset` — хеш ассета, из которого
 * ставили в прошлый раз: изменился ассет (обновилось приложение) — ставим из него,
 * а всё докачанное выбрасываем: релиз новее. `seed.db.stamp` — хеш того, что стоит
 * сейчас; по нему воркер обновления решает, есть ли на сервере что-то новое.
 *
 * Версия определяется хешем, а не размером файла: SQLite выравнивает базу по страницам,
 * и правка текста внутри строки его не двигает — приложение осталось бы на старой
 * копии, причём молча.
 *
 * Докачанный файл живёт в `seed.db.next` до следующего запуска: под открытым
 * соединением базу не подменяют.
 */
object SeedInstaller {

    private const val ASSET_NAME = "seed.db"
    private const val ASSET_DIGEST = "seed.db.sha256"
    private const val DB_NAME = "seed.db"
    private const val STAMP_NAME = "seed.db.stamp"
    private const val ASSET_STAMP_NAME = "seed.db.asset"
    private const val NEXT_NAME = "seed.db.next"
    private const val NEXT_DIGEST_NAME = "seed.db.next.sha256"

    /** @return абсолютный путь к готовому к открытию seed.db */
    fun install(context: Context): String {
        val dir = context.filesDir
        val target = File(dir, DB_NAME)
        val assetStamp = File(dir, ASSET_STAMP_NAME)

        val expected = context.assets.open(ASSET_DIGEST).use { it.readBytes().decodeToString().trim() }

        if (target.exists() && assetStamp.exists() && assetStamp.readText() == expected) {
            promoteNext(dir)
            return target.absolutePath
        }

        // Пишем во временный файл и переименовываем: прерванное копирование не должно
        // оставить обрезанную базу, которую следующий запуск примет за целую.
        val tmp = File(dir, "$DB_NAME.tmp")
        context.assets.open(ASSET_NAME).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "не удалось установить seed.db в ${target.absolutePath}" }
        File(dir, NEXT_NAME).delete()
        File(dir, NEXT_DIGEST_NAME).delete()

        // Отметки пишутся последними: упади копирование — на следующем запуске
        // несовпадение отметки заставит повторить установку.
        File(dir, STAMP_NAME).writeText(expected)
        assetStamp.writeText(expected)

        return target.absolutePath
    }

    /** Хеш установленного справочника — с ним воркер сравнивает манифест сервера. */
    fun installedDigest(dir: File): String? = File(dir, STAMP_NAME).takeIf { it.isFile }?.readText()?.trim()

    /** Хеш уже докачанного, но ещё не вставшего файла. `null` — ничего не ждёт. */
    fun stagedDigest(dir: File): String? =
        if (File(dir, NEXT_NAME).isFile) File(dir, NEXT_DIGEST_NAME).takeIf { it.isFile }?.readText()?.trim() else null

    /**
     * Скачанный файл -> `seed.db.next`, если хеш сошёлся. Не сошёлся — файл удаляется:
     * обрезанная закачка не должна дожидаться следующего запуска.
     */
    fun stage(dir: File, downloaded: File, expectedSha256: String): Boolean {
        val actual = sha256(downloaded)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            downloaded.delete()
            return false
        }
        val next = File(dir, NEXT_NAME)
        next.delete()
        if (!downloaded.renameTo(next)) {
            downloaded.delete()
            return false
        }
        File(dir, NEXT_DIGEST_NAME).writeText(actual)
        return true
    }

    /** `seed.db.next` -> `seed.db`, если содержимое цело. Иначе `.next` выбрасывается. */
    fun promoteNext(dir: File): Boolean {
        val next = File(dir, NEXT_NAME)
        val digestFile = File(dir, NEXT_DIGEST_NAME)
        if (!next.isFile) {
            digestFile.delete()
            return false
        }
        val expected = digestFile.takeIf { it.isFile }?.readText()?.trim()
        if (expected == null || !sha256(next).equals(expected, ignoreCase = true)) {
            next.delete()
            digestFile.delete()
            return false
        }
        val target = File(dir, DB_NAME)
        if (target.exists()) target.delete()
        if (!next.renameTo(target)) {
            next.delete()
            digestFile.delete()
            return false
        }
        digestFile.delete()
        File(dir, STAMP_NAME).writeText(expected)
        return true
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
