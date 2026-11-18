package n7.kcalai.feature.scanner

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import n7.kcalai.model.Log
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferGetPlaneCount
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferGetWidthOfPlane
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * Кадр камеры -> разобранная таблица пищевой ценности.
 *
 * Своего движка на iOS не нужно: Vision знает кириллицу. Своё здесь только это —
 * достать из кадра яркость и строки. Что делать с мыльным кадром, с отсутствием
 * распознавания и с прочитанным, решает общий [LabelPipeline], тот же, что на Android.
 *
 * Пока считается один кадр, остальные отбрасываются сразу. Копить их нельзя:
 * распознавание идёт десятки миллисекунд, кадры приходят каждые тридцать, и
 * очередь росла бы быстрее, чем расходится.
 */
@OptIn(ExperimentalForeignApi::class)
internal class LabelAnalyzer(
    private val scope: CoroutineScope,
    /**
     * Читать этикетку. `false` — кадр ищет только штрих-код: шаг кода в том же
     * окне. Спрашивается на каждом кадре — окно переходит к карточке посреди
     * сессии, а камера и анализ при этом не пересоздаются.
     */
    private val reading: () -> Boolean,
    private val onFrame: (LabelFrame) -> Unit,
) {
    private val busy = atomic(false)
    private val pipeline = LabelPipeline()

    fun analyze(frame: CVPixelBufferRef, width: Int, height: Int) {
        if (!reading()) return
        if (!busy.compareAndSet(expect = false, update = true)) return

        // Резкость — здесь, на очереди камеры: буфер жив, пока не вернулся делегат.
        val sharpness = sharpnessOf(frame)
        scope.launch {
            val started = NSDate().timeIntervalSince1970
            fun elapsed() = ((NSDate().timeIntervalSince1970 - started) * MILLIS_PER_SECOND).toLong()
            try {
                if (sharpness != null && !pipeline.isSharp(sharpness)) {
                    onFrame(pipeline.blurred(elapsed()))
                    delay(LabelPipeline.SKIP_PAUSE_MS)
                    return@launch
                }

                val lines = recognizeLabel(frame, width, height)
                if (lines == null) {
                    pipeline.unavailable()?.let(onFrame)
                    return@launch
                }
                onFrame(pipeline.read(lines, elapsed(), "$width×$height").frame)
            } catch (error: Exception) {
                // Один плохой кадр — не повод закрывать камеру: следующий придёт через тридцать миллисекунд.
                Log.w(TAG, "кадр не разобран", error)
            } finally {
                busy.value = false
            }
        }
    }

    private companion object {
        const val TAG = "LabelAnalyzer"
        const val MILLIS_PER_SECOND = 1_000.0
    }
}

/**
 * Резкость кадра камеры для [FrameSharpness] — по яркости.
 *
 * Камера iOS отдаёт кадр в YUV: яркость — первая плоскость, и переводить
 * ничего не надо. Если кадр пришёл одной плоскостью BGRA, яркость считается
 * так же, как на Android. `null` — формат незнакомый: тогда кадр считается резким,
 * как было до проверки, и решает распознавание.
 */
@OptIn(ExperimentalForeignApi::class)
private fun sharpnessOf(frame: CVPixelBufferRef): Double? {
    CVPixelBufferLockBaseAddress(frame, kCVPixelBufferLock_ReadOnly)
    try {
        return if (CVPixelBufferGetPlaneCount(frame) > 0u) {
            val base = CVPixelBufferGetBaseAddressOfPlane(frame, 0u)?.reinterpret<UByteVar>() ?: return null
            lumaSharpness(
                base,
                width = CVPixelBufferGetWidthOfPlane(frame, 0u).toInt(),
                height = CVPixelBufferGetHeightOfPlane(frame, 0u).toInt(),
                stride = CVPixelBufferGetBytesPerRowOfPlane(frame, 0u).toInt(),
                bytesPerPixel = 1,
            )
        } else {
            val base = CVPixelBufferGetBaseAddress(frame)?.reinterpret<UByteVar>() ?: return null
            lumaSharpness(
                base,
                width = CVPixelBufferGetWidth(frame).toInt(),
                height = CVPixelBufferGetHeight(frame).toInt(),
                stride = CVPixelBufferGetBytesPerRow(frame).toInt(),
                bytesPerPixel = BGRA_BYTES,
            )
        }
    } finally {
        CVPixelBufferUnlockBaseAddress(frame, kCVPixelBufferLock_ReadOnly)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun lumaSharpness(base: CPointer<UByteVar>, width: Int, height: Int, stride: Int, bytesPerPixel: Int): Double {
    val luma = IntArray(width * height)
    for (y in 0 until height) {
        val row = y * stride
        for (x in 0 until width) {
            val at = row + x * bytesPerPixel
            luma[y * width + x] = if (bytesPerPixel == 1) {
                base[at].toInt()
            } else {
                // BGRA: те же веса, что у Android.
                (base[at + 2].toInt() * 299 + base[at + 1].toInt() * 587 + base[at].toInt() * 114) / 1000
            }
        }
    }
    return FrameSharpness.of(luma, width, height)
}

private const val BGRA_BYTES = 4
