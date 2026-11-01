package n7.kcalai.feature.scanner

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import n7.kcalai.repositories.BarcodeValidator

/**
 * Распознаватель штрих-кода сам по себе, без привязки к источнику кадров.
 *
 * Кадры ему отдаёт [LabelAnalyzer] — на шаге кода только ему, в карточке — тот же
 * снимок, который уже развёрнут для распознавания текста. Окно съёмки одно,
 * и камера у него одна: второй раз брать кадр у камеры незачем, а заводить
 * второй экземпляр нативной модели — тем более.
 *
 * Форматы сужены до четырёх намеренно. На упаковках еды больше ничего
 * не встречается, а каждый лишний формат — это лишняя попытка разбора на кадре.
 */
internal class BarcodeReader {

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

    /**
     * Разбор асинхронный: [onCode] и [onDone] приходят на главном потоке ML Kit,
     * а не здесь. Один и тот же код прилетает подряд десятки раз — отсеивать
     * повторы должен вызывающий: здесь неизвестно, что с кодом собираются делать.
     */
    fun read(image: InputImage, onDone: () -> Unit = {}, onCode: (String) -> Unit) {
        scanner.process(image)
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
            .addOnCompleteListener { onDone() }
    }

    /** Тот же разбор по готовому, уже развёрнутому снимку — им пользуется съёмка этикетки. */
    fun read(bitmap: Bitmap, onCode: (String) -> Unit) {
        read(InputImage.fromBitmap(bitmap, 0), onCode = onCode)
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
