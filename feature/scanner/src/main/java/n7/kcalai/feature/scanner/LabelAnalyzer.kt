package n7.kcalai.feature.scanner

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import n7.kcalai.repositories.LabelParser
import n7.kcalai.repositories.LabelReading

/**
 * Кадр камеры -> разобранная таблица пищевой ценности.
 *
 * Распознаватель латинский, и другого быть не может: кириллицы у ML Kit нет.
 * Слова с русской этикетки он вернёт мусором или не вернёт вовсе — и это не мешает,
 * потому что нужны цифры, а цифры набраны латинскими глифами. Разбирается
 * результат в [LabelParser], которому буквы не нужны вообще.
 *
 * Те же две обязанности, что и у [BarcodeAnalyzer]. Первая — `proxy.close()`
 * в любом исходе, иначе очередь кадров встаёт намертво после третьего. Вторая —
 * отдавать результат каждый раз, а решать, что с ним делать, предоставлять
 * вызывающему: здесь неизвестно, ждут разбора целиком или уже хватит.
 *
 * Строки передаются в [LabelParser] в порядке, в котором их вернул распознаватель, —
 * сверху вниз и слева направо. Порядок значим: только им белок отличается
 * от углеводов, у которых одинаковый коэффициент в формуле Этуотера.
 */
internal class LabelAnalyzer(
    private val onReading: (LabelReading) -> Unit,
) : ImageAnalysis.Analyzer {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(proxy: ImageProxy) {
        val frame = proxy.image
        if (frame == null) {
            proxy.close()
            return
        }

        recognizer.process(InputImage.fromMediaImage(frame, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { text ->
                // Строки, а не блоки: в таблице пищевой ценности подпись и число
                // стоят в одной строке, и дробить её незачем.
                val lines = text.textBlocks
                    .flatMap { block -> block.lines }
                    .map { line -> line.text }

                onReading(LabelParser.parse(lines))
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** Закрывать обязательно: распознаватель держит нативную модель. */
    fun close() {
        recognizer.close()
    }
}
