package n7.kcalai.feature.scanner

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import n7.kcalai.ocr.LabelOcr
import n7.kcalai.repositories.LabelParser
import n7.kcalai.repositories.LabelReading

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
    private val onReading: (LabelReading) -> Unit,
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)

    override fun analyze(proxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }

        // Bitmap снимается до ухода в корутину: ImageProxy живёт только до close(),
        // а закрыть его надо не дожидаясь конца распознавания.
        val bitmap = try {
            proxy.toBitmap()
        } catch (error: Throwable) {
            busy.set(false)
            return
        } finally {
            proxy.close()
        }

        scope.launch {
            try {
                // null — OCR на этом устройстве недоступен. Не событие, а состояние:
                // молчим и даём работать разбору по цифрам через другой путь.
                val lines = ocr.read(bitmap) ?: return@launch
                onReading(LabelParser.parseLines(lines))
            } finally {
                busy.set(false)
            }
        }
    }
}
