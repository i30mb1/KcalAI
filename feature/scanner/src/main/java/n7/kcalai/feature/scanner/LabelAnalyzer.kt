package n7.kcalai.feature.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import n7.kcalai.ocr.LabelOcr
import n7.kcalai.repositories.LabelParser
import n7.kcalai.repositories.LabelReading

/**
 * Один разобранный кадр.
 *
 * @param elapsedMs сколько занял кадр целиком — распознавание плюс разбор.
 *        Не украшение: инференс идёт десятки миллисекунд, и когда съёмка
 *        начинает тормозить, узнать об этом надо не из ощущений.
 * @param available OCR поднялся на этом устройстве. `false` — не сбой, а режим:
 *        нативной библиотеки под этот ABI может не быть вовсе.
 */
internal class LabelFrame(
    /** Уже согласованное несколькими кадрами — см. [LabelConsensus]. */
    val reading: LabelReading,
    val elapsedMs: Long,
    val available: Boolean = true,
    /** Размер кадра, ушедшего в распознавание, — уже после поворота. */
    val size: String = "",
    /** Насколько набрано согласие по каждому из четырёх значений. */
    val fields: List<Pair<String, LabelConsensus.Field>> = emptyList(),
    val frames: Int = 0,
)

/**
 * Кадр камеры -> разобранная таблица пищевой ценности.
 *
 * Движок PP-OCRv5 читает и кириллицу, и цифры, поэтому подписи «Белки» и «Жиры»
 * связываются со своими числами прямо, а не подбираются арифметикой. ML Kit здесь
 * больше не участвует: кириллицы он не знает ни в одном из своих скриптов.
 *
 * Две обязанности, как и у [BarcodeAnalyzer]. Первая — `proxy.close()` в любом
 * исходе, иначе очередь кадров встаёт намертво после третьего. Вторая — отдавать
 * результат каждый раз, а решать, довольно ли его, предоставлять вызывающему.
 *
 * Отличие от штрих-кода одно, и оно определяет устройство класса: инференс идёт
 * десятки миллисекунд, а кадры приходят каждые тридцать. Поэтому пока считается
 * один кадр, остальные **отбрасываются сразу**. Копить их нельзя: очередь растёт
 * быстрее, чем расходится, и превью встаёт.
 */
internal class LabelAnalyzer(
    private val ocr: LabelOcr,
    private val scope: CoroutineScope,
    /** Куда складывать последние секунды съёмки для разбора потом. */
    private val recorder: LabelRecorder?,
    private val onFrame: (LabelFrame) -> Unit,
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)
    private val reportedUnavailable = AtomicBoolean(false)

    /**
     * Несколько кадров вместо одного.
     *
     * Живёт здесь, а не в UI, потому что кадры сюда и приходят. Обращается к нему
     * только тело корутины, которое [busy] держит в одном экземпляре за раз.
     */
    private val consensus = LabelConsensus()

    /** Последнее, что попало в лог. Пишем на смену результата, а не на кадр — см. [log]. */
    private var lastLogged: String? = null

    override fun analyze(proxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }

        // Bitmap снимается до ухода в корутину: ImageProxy живёт только до close(),
        // а закрыть его надо не дожидаясь конца распознавания. Поворот кадра
        // читается там же и по той же причине — он живёт в ImageProxy.
        val rotation = proxy.imageInfo.rotationDegrees
        val frame = try {
            proxy.toBitmap()
        } catch (error: Throwable) {
            busy.set(false)
            return
        } finally {
            proxy.close()
        }

        scope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                val bitmap = frame.upright(rotation)

                // null — OCR на этом устройстве недоступен. Не событие, а состояние,
                // и сказать о нём надо один раз: дальше кадры пойдут те же.
                val lines = ocr.read(bitmap)
                if (lines == null) {
                    if (reportedUnavailable.compareAndSet(false, true)) {
                        Log.w(TAG, "OCR недоступен — этикетку прочитать нечем")
                        onFrame(LabelFrame(LabelReading.EMPTY, 0, available = false))
                    }
                    return@launch
                }

                val verdict = consensus.add(LabelParser.parseLines(lines))
                val elapsed = SystemClock.elapsedRealtime() - started
                val size = "${bitmap.width}×${bitmap.height}"

                // Кадр кладётся вместе с тем, что из него вышло: снимок без
                // расшифровки говорит только «вот что было видно», а с ней —
                // «вот что было видно и вот где разбор ошибся».
                recorder?.add(bitmap, started, verdict.reading.summary())

                log(verdict, elapsed, size)
                onFrame(
                    LabelFrame(
                        reading = verdict.reading,
                        elapsedMs = elapsed,
                        size = size,
                        fields = verdict.fields,
                        frames = verdict.frames,
                    )
                )
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * Лог пишется на смену разбора, а не на кадр.
     *
     * Кадров десятки в секунду, и подавляющее большинство повторяют предыдущий:
     * камера стоит, этикетка та же. Лог на каждый кадр не читается вовсе —
     * нужное в нём тонет за секунду. Лог на изменение показывает ровно то,
     * ради чего его и открывают: что поменялось и от чего.
     */
    private fun log(verdict: LabelConsensus.Verdict, elapsedMs: Long, size: String) {
        val reading = verdict.reading
        val summary = reading.summary()
        if (summary == lastLogged) return
        lastLogged = summary

        Log.d(TAG, "$summary · $elapsedMs мс · кадр $size")
        Log.d(TAG, "голоса за ${verdict.frames} кадров: $verdict")
        Log.d(TAG, "строки: ${reading.trace.lines.joinToString(" | ")}")
        if (reading.trace.zeroedLabels.isNotEmpty()) {
            // Самый неочевидный шаг разбора: подписи на этикетке нет — значит,
            // макроса в продукте нет, и правильный ответ ноль. Ошибись он здесь —
            // по одним только числам в форме этого не увидеть.
            Log.d(TAG, "принято за ноль: ${reading.trace.zeroedLabels.joinToString(", ")}")
        }
    }

    private companion object {
        const val TAG = "LabelScan"
    }
}

/**
 * Кадр камеры, повёрнутый так, как его видит человек.
 *
 * Сенсор смонтирован боком почти во всех телефонах, и в портретной ориентации
 * CameraX отдаёт кадр, лежащий на боку, приложив к нему угол поворота. Превью
 * этот угол применяет само, поэтому на экране всё ровно, а в анализ уходит
 * повёрнутое — и распознаватель получает вертикальный текст.
 *
 * Детектор такой кадр ещё как-то размечает, но распознающая модель обучена
 * на горизонтальных строках и отдаёт по ним мусор. Со стороны это выглядит
 * ровно как «текст не читается», хотя читается он прекрасно — просто не тот.
 */
private fun Bitmap.upright(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (rotated !== this) recycle()
    return rotated
}

/** Строка разбора для лога и для экрана: одна и та же, чтобы их можно было сличать. */
internal fun LabelReading.summary(): String = buildString {
    append(trace.route.title)
    append(if (confident) " · сошлось" else " · не сошлось")
    append(" · ккал ${draft.kcal100 ?: "—"}")
    append(" · Б ${draft.prot100.grams()}")
    append(" · Ж ${draft.fat100.grams()}")
    append(" · У ${draft.carb100.grams()}")
}

/** Сотые грамма в читаемые граммы: «5», а не «5.0», и «—» вместо пустоты. */
internal fun Int?.grams(): String = when {
    this == null -> "—"
    this % 100 == 0 -> (this / 100).toString()
    else -> (this / 100.0).toString()
}
