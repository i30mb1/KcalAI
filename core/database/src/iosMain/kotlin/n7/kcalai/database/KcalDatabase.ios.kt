package n7.kcalai.database

import androidx.room.Room
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Дневник лежит в Application Support, а не в Documents: Documents человек видит
 * в «Файлах», и база там выглядела бы документом, который можно переложить
 * или стереть.
 */
fun buildKcalDatabase(): KcalDatabase =
    Room.databaseBuilder<KcalDatabase>(name = databasePath("kcal.db"))
        .configured()

/**
 * Опт-ин нужен из-за `NSError**` последним параметром: в Kotlin это указатель,
 * а указатели в cinterop ещё помечены экспериментальными. Ошибку мы и не читаем —
 * папка либо уже есть, либо создаётся, и в обоих случаях делать с ней нечего.
 */
@OptIn(ExperimentalForeignApi::class)
private fun databasePath(name: String): String {
    val support = NSSearchPathForDirectoriesInDomains(
        directory = NSApplicationSupportDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String ?: error("нет папки Application Support")

    // Саму папку iOS не создаёт: без неё Room упадёт на открытии файла.
    NSFileManager.defaultManager.createDirectoryAtPath(support, true, null, null)

    return "$support/$name"
}
