package n7.kcalai.feature.scanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Последние секунды съёмки, сложенные на диск для разбора потом.
 *
 * Распознавание правится по тому, что оно на самом деле видело, а не по тому,
 * что человек запомнил. «Не читает творожок» — это не отчёт об ошибке: непонятно,
 * подвёл фокус, блик, наклон, разрешение или разбор. Кадры отвечают на это сразу
 * и однозначно, а рядом лежит расшифровка — что распознаватель прочёл и что
 * из этого собрал разбор.
 *
 * Сохраняются **те самые кадры, что ушли в модель**, — после поворота и в том
 * разрешении, в котором она их видела. Не превью, не снимок по кнопке: они
 * выглядят иначе и диагностируют не то.
 *
 * Не видеофайл, а последовательность кадров, и это осознанно. Анализ идёт
 * впятеро реже частоты камеры, так что «видео» тут в любом случае набор
 * отдельных снимков; зато каждый ложится прямо в `androidTest/assets/labels`
 * и становится тестом, а из mp4 его пришлось бы выковыривать. Собрать ролик,
 * если захочется посмотреть, можно одной строкой ffmpeg — рядом лежит подсказка.
 *
 * Живёт скользящим окном: держим последние [windowMs] миллисекунд и выбрасываем
 * всё, что старше. Интересен конец съёмки — момент, когда разбор либо сошёлся,
 * либо человек сдался, — а не первые секунды наведения на пачку.
 */
internal class LabelRecorder(
    private val root: File,
    private val windowMs: Long = WINDOW_MS,
) {

    private class Shot(val atMs: Long, val jpeg: ByteArray, val note: String)

    private val shots = ArrayDeque<Shot>()

    /**
     * Кладёт кадр в окно.
     *
     * Сжатие идёт здесь, на потоке анализа, и это дешевле, чем кажется: кадр
     * в JPEG — единицы миллисекунд против полутора-двух сотен на распознавание.
     * Держать же полтора десятка несжатых кадров по пять мегабайт нельзя —
     * это семьдесят мегабайт живой памяти ради диагностики.
     */
    @Synchronized
    fun add(bitmap: Bitmap, atMs: Long, note: String) {
        val jpeg = ByteArrayOutputStream(JPEG_HINT_BYTES).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        shots.addLast(Shot(atMs, jpeg, note))
        while (shots.isNotEmpty() && atMs - shots.first().atMs > windowMs) {
            shots.removeFirst()
        }
    }

    /** Забирает накопленное и очищает окно — под замком, съёмка может ещё идти. */
    @Synchronized
    private fun drain(): List<Shot> = shots.toList().also { shots.clear() }

    /**
     * Пишет окно в отдельный каталог сессии.
     *
     * Вызывать с фонового потока: тут несколько мегабайт.
     */
    fun save(startedAtMs: Long): File? {
        val taken = drain()
        if (taken.isEmpty()) return null

        return try {
            val dir = File(root, "scan-$startedAtMs").apply { mkdirs() }
            val notes = StringBuilder()
            taken.forEachIndexed { index, shot ->
                val name = "frame-%02d.jpg".format(index)
                File(dir, name).writeBytes(shot.jpeg)
                notes.append("$name  +${shot.atMs - taken.first().atMs} мс  ${shot.note}\n")
            }
            notes.append(
                "\nСобрать ролик: ffmpeg -framerate 5 -i frame-%02d.jpg -pix_fmt yuv420p scan.mp4\n"
            )
            File(dir, "readings.txt").writeText(notes.toString())

            prune()
            Log.i(TAG, "кадры съёмки сохранены: ${dir.absolutePath} (${taken.size} шт.)")
            dir
        } catch (error: Throwable) {
            // Диагностика не вправе ронять съёмку: нет места, нет прав — молчим.
            Log.w(TAG, "не удалось сохранить кадры съёмки", error)
            null
        }
    }

    /**
     * Оставляет только последние сессии.
     *
     * Иначе диагностика тихо съедает память телефона: три мегабайта за съёмку,
     * а съёмок за день десятки.
     */
    private fun prune() {
        val sessions = root.listFiles { file -> file.isDirectory }?.sortedBy { it.name }.orEmpty()
        sessions.dropLast(KEEP_SESSIONS).forEach { it.deleteRecursively() }
    }

    companion object {
        private const val TAG = "LabelScan"

        /** Три секунды: столько человек держит камеру на пачке, прежде чем понять, что не выходит. */
        private const val WINDOW_MS = 3_000L

        private const val JPEG_QUALITY = 80
        private const val JPEG_HINT_BYTES = 256 * 1024
        private const val KEEP_SESSIONS = 5

        /** Куда складывать. Внешний каталог приложения — его видно с компьютера без root. */
        fun directory(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "label-scans")
    }
}
