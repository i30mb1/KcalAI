package n7.kcalai.feature.scanner

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation

/**
 * Превью камеры и разбор её кадров.
 *
 * Официальный `CameraXViewfinder` вместо `PreviewView`, завёрнутого в `AndroidView`.
 * Разница не в опрятности: у вью, случайно попавшей в композицию, собственные
 * правила жизни surface, и согласовывать их с правилами композиции приходится
 * руками — а расплата за несогласованность выглядит как рывок картинки в момент,
 * когда человек наводит камеру.
 *
 * Кадр в анализ приходит уже повёрнутым: [ImageAnalysis.Builder.setOutputImageRotationEnabled]
 * просит об этом саму камеру. Прошлый путь через `CameraController` такого
 * не умел, и разворачивать кадр приходилось своим `Canvas` — лишняя копия
 * на пять мегабайт на каждом кадре.
 *
 * @param analysis собрать анализ кадров. Зовётся один раз за сессию: внутри
 *        живут нативные модели и потоки, и пересоздавать их на перерисовке нельзя.
 */
@Composable
internal fun CameraFrames(
    modifier: Modifier = Modifier,
    analysis: () -> FrameAnalysis,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val frames = remember { analysis() }
    DisposableEffect(frames) { onDispose { frames.close() } }

    var request by remember { mutableStateOf<SurfaceRequest?>(null) }

    LaunchedEffect(lifecycleOwner, frames) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { incoming -> request = incoming }
        }
        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                frames.useCase,
            )
            // Сессия живёт, пока жив экран. Отвязка — в finally, чтобы камера
            // отпускалась и при обычном закрытии, и при отмене корутины.
            awaitCancellation()
        } finally {
            provider.unbindAll()
        }
    }

    request?.let { surface ->
        CameraXViewfinder(surfaceRequest = surface, modifier = modifier.fillMaxSize())
    }
}

/**
 * Анализ кадров вместе со всем, что закрывается вместе с ним.
 *
 * Анализатор снимается первым, а уже потом освобождаются нативные сессии:
 * иначе кадр, стоящий в очереди, придёт в закрытый движок.
 */
internal class FrameAnalysis(
    val useCase: ImageAnalysis,
    private val onClose: () -> Unit,
) {
    fun close() {
        useCase.clearAnalyzer()
        onClose()
    }
}
