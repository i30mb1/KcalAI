package n7.kcalai.feature.scanner

import kotlinx.cinterop.ExperimentalForeignApi
import n7.kcalai.model.Log
import n7.kcalai.repositories.BarcodeValidator
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeEAN13Code
import platform.AVFoundation.AVMetadataObjectTypeEAN8Code
import platform.AVFoundation.AVMetadataObjectTypeUPCECode
import platform.AVFoundation.position
import platform.AVFoundation.videoZoomFactor
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create

/**
 * Камера: превью, штрих-код и кадры этикетки из одной сессии.
 *
 * Сессия одна на всё окно, как и на Android: шаг кода и шаг этикетки сменяют
 * друг друга, а камера при этом не гаснет и не запускается заново.
 *
 * Кадры этикетки и метаданные кода идут разными выходами: штрих-код читает сама
 * система, и своего распознавателя для него не нужно.
 */
@OptIn(ExperimentalForeignApi::class)
internal class CameraSession(
    private val onBarcode: (String) -> Unit,
    private val onFrame: (frame: platform.CoreVideo.CVPixelBufferRef, width: Int, height: Int) -> Unit,
) {
    val session = AVCaptureSession()

    /** Свой поток: разбор кадра нельзя делать на главном — встанет вся вёрстка. */
    private val queue = dispatch_queue_create("n7.kcalai.camera", null)

    private var device: AVCaptureDevice? = null

    private val codes = object : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
        override fun captureOutput(
            output: AVCaptureOutput,
            didOutputMetadataObjects: List<*>,
            fromConnection: AVCaptureConnection,
        ) {
            didOutputMetadataObjects
                .filterIsInstance<AVMetadataMachineReadableCodeObject>()
                .forEach { code ->
                    // Приведение к EAN-13 и контрольная цифра — общим кодом: не прошедший
                    // её не должен покидать сканер, на пачке рядом бывает и не GTIN.
                    code.stringValue?.let { raw -> BarcodeValidator.normalize(raw)?.let(onBarcode) }
                }
        }
    }

    private val frames = object : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
        override fun captureOutput(
            output: AVCaptureOutput,
            didOutputSampleBuffer: CMSampleBufferRef?,
            fromConnection: AVCaptureConnection,
        ) {
            val pixels = didOutputSampleBuffer?.let(::CMSampleBufferGetImageBuffer) ?: return
            onFrame(pixels, CVPixelBufferGetWidth(pixels).toInt(), CVPixelBufferGetHeight(pixels).toInt())
        }
    }

    /** @return удалось ли открыть камеру. `false` — остаётся ручной ввод, а не тупик. */
    fun start(): Boolean {
        val camera = AVCaptureDevice.devicesWithMediaType(AVMediaTypeVideo)
            .filterIsInstance<AVCaptureDevice>()
            .firstOrNull { it.position == platform.AVFoundation.AVCaptureDevicePositionBack }
            ?: return false
        device = camera

        val input = AVCaptureDeviceInput.deviceInputWithDevice(camera, null) ?: return false
        session.beginConfiguration()
        session.sessionPreset = AVCaptureSessionPresetHigh
        if (!session.canAddInput(input)) {
            session.commitConfiguration()
            return false
        }
        session.addInput(input)

        val metadata = AVCaptureMetadataOutput()
        if (session.canAddOutput(metadata)) {
            session.addOutput(metadata)
            metadata.setMetadataObjectsDelegate(codes, queue)
            // Только линейные коды товаров: QR на пачке — это не её штрих-код.
            metadata.metadataObjectTypes = listOf(
                AVMetadataObjectTypeEAN13Code,
                AVMetadataObjectTypeEAN8Code,
                AVMetadataObjectTypeUPCECode,
            )
        }

        val video = AVCaptureVideoDataOutput()
        if (session.canAddOutput(video)) {
            // Пока считается кадр, следующие отбрасываются: инференс идёт десятки
            // миллисекунд, а кадры приходят каждые тридцать — копить их нельзя,
            // очередь растёт быстрее, чем расходится, и превью встаёт.
            video.alwaysDiscardsLateVideoFrames = true
            video.setSampleBufferDelegate(frames, queue)
            session.addOutput(video)
        }
        session.commitConfiguration()

        session.startRunning()
        return true
    }

    /** Приближение к таблице: та же шкала, что у кнопки на Android. */
    fun zoom(factor: Float) {
        val camera = device ?: return
        runCatching {
            camera.lockForConfiguration(null)
            camera.videoZoomFactor = factor.toDouble().coerceIn(1.0, camera.activeFormat.videoMaxZoomFactor)
            camera.unlockForConfiguration()
        }.onFailure { Log.w(TAG, "не удалось приблизить: $it") }
    }

    fun stop() {
        if (session.running) session.stopRunning()
    }

    private companion object {
        const val TAG = "CameraSession"
    }
}
