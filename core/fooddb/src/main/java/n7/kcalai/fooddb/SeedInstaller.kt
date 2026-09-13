package n7.kcalai.fooddb

import android.content.Context
import java.io.File

/**
 * Раскладывает `seed.db` из assets в `filesDir`.
 *
 * SQLite не умеет открывать файл внутри APK, поэтому справочник обязан оказаться
 * на файловой системе.
 *
 * Версия определяется хешем, который кладёт рядом сборщик базы. Размер файла для
 * этого не годится: SQLite выравнивает базу по страницам, и правка текста внутри
 * строки его не двигает — приложение осталось бы на старой копии, причём молча.
 */
object SeedInstaller {

    private const val ASSET_NAME = "seed.db"
    private const val ASSET_DIGEST = "seed.db.sha256"
    private const val DB_NAME = "seed.db"
    private const val STAMP_NAME = "seed.db.stamp"

    /** @return абсолютный путь к готовому к открытию seed.db */
    fun install(context: Context): String {
        val target = File(context.filesDir, DB_NAME)
        val stamp = File(context.filesDir, STAMP_NAME)

        val expected = context.assets.open(ASSET_DIGEST).use { it.readBytes().decodeToString().trim() }

        if (target.exists() && stamp.exists() && stamp.readText() == expected) {
            return target.absolutePath
        }

        // Пишем во временный файл и переименовываем: прерванное копирование не должно
        // оставить обрезанную базу, которую следующий запуск примет за целую.
        val tmp = File(context.filesDir, "$DB_NAME.tmp")
        context.assets.open(ASSET_NAME).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "не удалось установить seed.db в ${target.absolutePath}" }

        // Отметка пишется последней: упади копирование — на следующем запуске
        // несовпадение отметки заставит повторить установку.
        stamp.writeText(expected)

        return target.absolutePath
    }
}
