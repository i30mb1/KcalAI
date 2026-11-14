package n7.kcalai.feature.scanner

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import n7.kcalai.model.Log
import n7.kcalai.model.TextLine
import platform.CoreGraphics.CGRect
import platform.CoreVideo.CVPixelBufferRef
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import kotlin.coroutines.resume

/**
 * Кадр камеры -> строки таблицы пищевой ценности.
 *
 * Vision знает кириллицу, в отличие от ML Kit, — поэтому на iOS не нужен ни свой
 * движок, ни нативная библиотека: подписи «Белки» и «Жиры» читаются прямо.
 * Дальше кадр уходит в общий `LabelParser`, тот же, что на Android: геометрия
 * строк в [TextLine] — всё, что ему нужно.
 *
 * @param width ширина кадра в пикселях, [height] — высота. Vision отдаёт
 *        координаты долями кадра, а разбору нужны пиксели снимка.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun recognizeLabel(
    frame: CVPixelBufferRef,
    width: Int,
    height: Int,
): List<TextLine>? = suspendCancellableCoroutine { continuation ->
    val request = VNRecognizeTextRequest { request, error ->
        if (error != null) {
            Log.w(TAG, "Vision не прочла кадр: ${error.localizedDescription}")
            continuation.resume(null)
            return@VNRecognizeTextRequest
        }
        val lines = request?.results.orEmpty()
            .filterIsInstance<VNRecognizedTextObservation>()
            .mapNotNull { it.toTextLine(width, height) }
        continuation.resume(lines)
    }
    request.recognitionLevel = VNRequestTextRecognitionLevelAccurate
    // Русский первым: на пачке он и напечатан, а латиница встречается в брендах.
    request.recognitionLanguages = listOf("ru-RU", "en-US")
    // Состав печатают мелко и в столбик, и словарь чаще портит числа, чем помогает.
    request.usesLanguageCorrection = false

    val handler = VNImageRequestHandler(cVPixelBuffer = frame, options = emptyMap<Any?, Any?>())
    runCatching { handler.performRequests(listOf(request), null) }
        .onFailure {
            Log.w(TAG, "Vision не запустилась: $it")
            if (continuation.isActive) continuation.resume(null)
        }
}

/**
 * Наблюдение Vision -> строка с геометрией.
 *
 * Начало координат у Vision в левом нижнем углу, а у разбора — в левом верхнем:
 * без переворота по вертикали таблица читалась бы снизу вверх, и подписи
 * достались бы чужим числам.
 */
@OptIn(ExperimentalForeignApi::class)
private fun VNRecognizedTextObservation.toTextLine(width: Int, height: Int): TextLine? {
    val best = topCandidates(1u).firstOrNull() as? platform.Vision.VNRecognizedText ?: return null
    val text = best.string
    if (text.isBlank()) return null

    return boundingBox.useContents {
        visionTextLine(
            text = text,
            confidence = best.confidence,
            boxX = origin.x,
            boxBottom = origin.y,
            boxWidth = size.width,
            boxHeight = size.height,
            width = width,
            height = height,
        )
    }
}

/**
 * Прямоугольник Vision -> строка с геометрией в пикселях снимка.
 *
 * Отдельно от cinterop, чтобы переворот проверялся тестом: начало координат
 * у Vision в левом нижнем углу, а у разбора — в левом верхнем, и без переворота
 * таблица читалась бы снизу вверх, а подписи достались бы чужим числам.
 *
 * @param boxBottom нижний край прямоугольника долей кадра, считая снизу
 */
internal fun visionTextLine(
    text: String,
    confidence: Float,
    boxX: Double,
    boxBottom: Double,
    boxWidth: Double,
    boxHeight: Double,
    width: Int,
    height: Int,
): TextLine {
    val pixelWidth = boxWidth * width
    val pixelHeight = boxHeight * height
    return TextLine(
        text = text,
        centerX = (boxX * width + pixelWidth / 2).toFloat(),
        centerY = ((1.0 - boxBottom - boxHeight) * height + pixelHeight / 2).toFloat(),
        width = pixelWidth.toFloat(),
        height = pixelHeight.toFloat(),
        confidence = confidence,
    )
}

private const val TAG = "LabelText"
