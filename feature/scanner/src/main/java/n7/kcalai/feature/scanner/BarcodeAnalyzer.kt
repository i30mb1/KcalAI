package n7.kcalai.feature.scanner

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import n7.kcalai.repositories.BarcodeValidator

/**
 * Кадр камеры -> проверенный GTIN.
 *
 * Распознавание запускается на каждом кадре, поэтому важны две вещи. Первая —
 * `proxy.close()` обязан выполниться в любом исходе, иначе очередь кадров встаёт
 * намертво после третьего. Вторая — [onCode] может прийти несколько раз подряд
 * на один и тот же товар, и отсеивать повторы должен вызывающий: здесь неизвестно,
 * что с кодом собираются делать.
 *
 * Форматы сужены до четырёх намеренно. На упаковках еды больше ничего не встречается,
 * а каждый лишний формат — это лишняя попытка разбора на каждом кадре.
 */
internal class BarcodeAnalyzer(
    private val onCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
            )
            .build()
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(proxy: ImageProxy) {
        val frame = proxy.image
        if (frame == null) {
            proxy.close()
            return
        }

        scanner.process(InputImage.fromMediaImage(frame, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { barcodes ->
                // Контрольная цифра здесь не перестраховка: смазанную полосу
                // изредка читают не так, и битый код увёл бы человека заводить
                // продукт под номером, которого не существует.
                barcodes.asSequence()
                    .mapNotNull { barcode -> barcode.rawValue?.let { barcode.format to it } }
                    .mapNotNull { (format, value) -> normalize(format, value) }
                    .firstOrNull()
                    ?.let(onCode)
            }
            .addOnCompleteListener { proxy.close() }
    }

    /**
     * Распознаватель сообщает формат, и это не формальность.
     *
     * Восьмизначный UPC-E сплошь и рядом проходит ещё и контрольную сумму EAN-8,
     * так что по одним цифрам их не разделить. Здесь формат известен точно —
     * значит, разворачивать сжатую запись надо именно здесь, а не гадать позже.
     */
    private fun normalize(format: Int, value: String): String? =
        if (format == Barcode.FORMAT_UPC_E) {
            BarcodeValidator.normalizeUpcE(value)
        } else {
            BarcodeValidator.normalize(value)
        }

    /** Закрывать обязательно: распознаватель держит нативную модель. */
    fun close() {
        scanner.close()
    }
}
