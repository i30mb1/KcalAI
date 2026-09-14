package n7.kcalai.feature.scanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
 * Окно — [windowMs] миллисекунд **до последнего кадра с этикеткой** и ещё
 * [TAIL_MS] после него, а не последние секунды перед закрытием экрана. Разница
 * решающая. Человек сдаётся, опускает телефон и тянется к крестику — и три
 * секунды перед закрытием камера смотрит в пол и на полки с ценниками. Записанные
 * так сессии показывали этикетку в первом кадре и ноги в остальных семи,
 * а диагностировать по ногам нечего. Пока этикетки не было вовсе, окно
 * ведёт себя по-старому: последние секунды перед закрытием — лучше, чем ничего.
 */
internal class LabelRecorder(
    private val root: File,
    private val windowMs: Long = WINDOW_MS,
) {

    private class Shot(val atMs: Long, val jpeg: ByteArray, val note: String)

    /** Последние [windowMs] съёмки — запас, из которого нарезается окно вокруг этикетки. */
    private val recent = ArrayDeque<Shot>()

    /** Окно вокруг последней этикетки. `null`, пока этикетки в кадре не было. */
    private var kept: MutableList<Shot>? = null

    private var labelAtMs = 0L

    /**
     * Сжатие идёт своим потоком, а не тем, что разбирает кадр.
     *
     * Двадцать миллисекунд на кадр — вроде мелочь, но они ложатся ровно туда же,
     * где уже стоит распознавание, и складываются с ним. Диагностика не вправе
     * замедлять то, что диагностирует: рядом человек держит камеру на пачке.
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "label-recorder").apply { priority = Thread.MIN_PRIORITY }
    }

    /** Сколько несжатых кадров разрешено держать в очереди. */
    private val queued = AtomicInteger(0)

    /**
     * Кладёт кадр в окно.
     *
     * Битмап уезжает в очередь как есть — это пять мегабайт, поэтому очередь
     * ограничена двумя. Если сжатие отстаёт, кадр просто теряется: диагностике
     * дырка в записи не страшна, а лишние пятнадцать мегабайт живой памяти
     * посреди съёмки — вполне.
     *
     * @param label в кадре была этикетка — см. [LabelAnalyzer]. Такой кадр
     *        переносит окно: всё, что старше [windowMs] до него, забывается.
     */
    fun add(bitmap: Bitmap, atMs: Long, note: String, label: Boolean) {
        if (queued.get() >= MAX_QUEUED) return
        queued.incrementAndGet()
        try {
            worker.execute {
                try {
                    compress(bitmap, atMs, note, label)
                } finally {
                    queued.decrementAndGet()
                }
            }
        } catch (rejected: RejectedExecutionException) {
            // Съёмка уже закрывается. Кадр не нужен.
            queued.decrementAndGet()
        }
    }

    @Synchronized
    private fun compress(bitmap: Bitmap, atMs: Long, note: String, label: Boolean) {
        val jpeg = ByteArrayOutputStream(JPEG_HINT_BYTES).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        val shot = Shot(atMs, jpeg, note)
        recent.addLast(shot)
        while (recent.isNotEmpty() && atMs - recent.first().atMs > windowMs) {
            recent.removeFirst()
        }

        // Этикетка в кадре — окно переезжает сюда: последние секунды перед ней
        // это подход к пачке, а после неё дописывается только короткий хвост.
        // Дальше камера смотрит в пол, и копить это незачем.
        if (label) {
            labelAtMs = atMs
            kept = recent.toMutableList()
        } else if (atMs - labelAtMs <= TAIL_MS) {
            kept?.add(shot)
        }
    }

    /** Забирает накопленное и очищает окно — под замком, съёмка может ещё идти. */
    @Synchronized
    private fun drain(): List<Shot> = (kept ?: recent.toList()).also {
        recent.clear()
        kept = null
    }

    /**
     * Пишет окно в отдельный каталог сессии.
     *
     * Вызывать с фонового потока: тут несколько мегабайт.
     */
    fun save(startedAtMs: Long, outcome: String? = null): File? {
        // Дождаться хвоста очереди: последние кадры — самые интересные, на них
        // разбор либо сошёлся, либо человек сдался.
        worker.shutdown()
        runCatching { worker.awaitTermination(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }

        val taken = drain()
        if (taken.isEmpty()) return null

        return try {
            val dir = File(root, "scan-$startedAtMs").apply { mkdirs() }
            val notes = StringBuilder()
            taken.forEachIndexed { index, shot ->
                val name = "frame-%02d.jpg".format(index)
                File(dir, name).writeBytes(shot.jpeg)
                notes.append(name)
                    .append("  +")
                    .append(shot.atMs - taken.first().atMs)
                    .append(" мс  ")
                    // Отчёт по кадру многострочный, и продолжения отбиваются
                    // отступом: иначе имя кадра тонет в строках распознавания.
                    .append(shot.note.trimEnd().replace("\n", "\n" + INDENT))
                    .append("\n\n")
            }

            // Итог идёт последним, а не первым: читают файл сверху вниз, от того,
            // что видела камера, к тому, чем всё кончилось.
            outcome?.let { notes.append("итог: ").append(it.trimEnd()).append("\n\n") }
            // Частота примерно та, с какой кадры и приходили: анализ идёт около
            // шести десятых секунды на кадр, так что ролик получится в реальном
            // времени, а не ускоренным вчетверо.
            notes.append(
                "\nСобрать ролик: ffmpeg -framerate 2 -i frame-%02d.jpg -pix_fmt yuv420p scan.mp4\n"
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

        /** Сколько дописывать после того, как этикетка ушла из кадра: момент, когда человек сдался. */
        private const val TAIL_MS = 1_000L

        private const val JPEG_QUALITY = 80
        private const val JPEG_HINT_BYTES = 256 * 1024
        private const val KEEP_SESSIONS = 5

        /** Больше двух несжатых кадров в очереди — уже пятнадцать мегабайт. */
        private const val MAX_QUEUED = 2

        private const val DRAIN_TIMEOUT_MS = 2_000L

        /** Отступ продолжений многострочной заметки о кадре. */
        private const val INDENT = "              "

        fun directory(context: Context): File = labelScansDirectory(context)
    }
}

/**
 * Каталог сессий съёмки: внутреннее хранилище приложения.
 *
 * На снимках этикеток попадает кухня, руки и всё, что оказалось за пачкой,
 * — то есть кадры из чужой квартиры. Внешний каталог приложения хоть
 * и песочница, но лежит на общем разделе: его читает любой файловый
 * менеджер и видно по USB. Диагностике этого не нужно, а человеку такое
 * соседство никто не обещал.
 *
 * Публичный, потому что читает его не только записывающий: воркер отправки
 * в `:app` обходит его и ставит маркеры `.uploaded`.
 *
 * Забрать с отладочной сборки всё равно можно, просто через `run-as`:
 * `adb exec-out run-as n7.kcalai tar c files/label-scans | tar x`
 */
fun labelScansDirectory(context: Context): File = File(context.filesDir, "label-scans")
