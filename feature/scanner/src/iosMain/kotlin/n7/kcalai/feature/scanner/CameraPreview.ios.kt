package n7.kcalai.feature.scanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIView

/**
 * Живая картинка с камеры.
 *
 * Слой превью лежит в обычной `UIView`, а не рисуется Compose: кадры с камеры
 * система отдаёт прямо в слой, и путь через Skia добавил бы копию кадра
 * и задержку на каждом.
 *
 * Сессия приходит снаружи и переживает смену шага: окно одно — от штрих-кода
 * до карточки, — и гаснуть камере между ними незачем.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun CameraPreview(session: CameraSession, modifier: Modifier = Modifier) {
    val layer = remember(session) {
        AVCaptureVideoPreviewLayer(session = session.session).apply {
            // Кадр закрывает экран целиком, обрезая края: пустых полос по бокам
            // у камеры не бывает.
            videoGravity = AVLayerVideoGravityResizeAspectFill
        }
    }

    DisposableEffect(session) {
        onDispose { session.stop() }
    }

    UIKitView(
        factory = {
            UIView(frame = CGRectZero.readValue()).apply {
                layer.frame = bounds
                this.layer.addSublayer(layer)
            }
        },
        modifier = modifier,
        onResize = { view, rect ->
            // Слой не знает об изменении размеров сам: без этого картинка
            // осталась бы в исходном прямоугольнике при повороте и клавиатуре.
            view.layer.setFrame(rect)
            layer.setFrame(rect)
        },
    )
}
