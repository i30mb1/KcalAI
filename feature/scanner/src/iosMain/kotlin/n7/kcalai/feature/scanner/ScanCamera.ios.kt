package n7.kcalai.feature.scanner

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import n7.kcalai.model.Log
import n7.kcalai.model.ProductDraft
import n7.kcalai.repositories.LabelReading
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * Камера на iOS: AVFoundation, штрих-код читает сама система, этикетку — Vision.
 * Окно вокруг неё общее, см. [ScannerDialog].
 *
 * Опт-ин — из-за буферов кадров: они приходят указателями, а указатели
 * в cinterop ещё помечены экспериментальными.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberScanCamera(
    reading: () -> Boolean,
    onBarcode: (String) -> Unit,
    onFrame: (LabelFrame) -> Unit,
    outcome: () -> String?,
): ScanCamera {
    val camera = remember { IosScanCamera() }
    val currentReading by rememberUpdatedState(reading)
    val currentBarcode by rememberUpdatedState(onBarcode)
    val currentFrame by rememberUpdatedState(onFrame)

    LaunchedEffect(Unit) {
        if (!camera.granted) {
            camera.granted = requestCamera()
            camera.answered = true
        }
    }

    val scope = rememberCoroutineScope()
    val session = remember(camera.granted) {
        if (!camera.granted) {
            null
        } else {
            val analyzer = LabelAnalyzer(
                scope = scope,
                reading = { currentReading() },
                onFrame = { frame -> currentFrame(frame) },
            )
            CameraSession(
                // Метаданные приходят на очереди камеры, а окно меняет своё состояние
                // только на главном потоке — как на Android.
                onBarcode = { code -> dispatch_async(dispatch_get_main_queue()) { currentBarcode(code) } },
                onFrame = analyzer::analyze,
            )
        }
    }
    DisposableEffect(session) {
        if (session != null && !session.start()) {
            Log.w(TAG, "камера не открылась — остаётся ручной ввод")
        }
        onDispose { session?.stop() }
    }
    camera.session = session
    return camera
}

private class IosScanCamera : ScanCamera {

    override var granted by mutableStateOf(cameraAuthorized())

    // Пока не спросили, показывать «доступа нет» рано: человек ещё не отвечал.
    override var answered by mutableStateOf(granted)

    var session: CameraSession? by mutableStateOf(null)

    /** Читателя этикеток по кадру на iOS пока нет: карточка ждёт камеру и человека. */
    override val secondOpinion: (suspend () -> ProductDraft?)? = null

    @Composable
    override fun Preview(modifier: Modifier, zoom: Float, onPinch: ((Float) -> Unit)?, fit: Float) {
        val current = session ?: return
        LaunchedEffect(current, zoom) { current.zoom(zoom) }
        CameraPreview(session = current, modifier = modifier.fillMaxSize())
    }
}

/**
 * Отладка разбора этикетки — инструмент под Android: там он показывает, что
 * прочёл свой движок и где разошлись кадры. Здесь читает Vision, своего движка нет,
 * и показывать нечего.
 */
@Composable
actual fun LabelDebugDialog(onRead: (LabelReading) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onDismiss() }
}

private fun cameraAuthorized(): Boolean =
    AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized

private suspend fun requestCamera(): Boolean = suspendCancellableCoroutine { continuation ->
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
        continuation.resume(granted)
    }
}

private const val TAG = "ScanCamera"
