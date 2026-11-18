package n7.kcalai.feature.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference
import n7.kcalai.model.ProductDraft
import androidx.compose.ui.util.lerp as lerpFloat

/**
 * Камера на Android: CameraX, штрих-код ML Kit, этикетка — свой движок распознавания.
 * Окно вокруг неё общее, см. [ScannerDialog].
 */
@Composable
internal actual fun rememberScanCamera(
    reading: () -> Boolean,
    onBarcode: (String) -> Unit,
    onFrame: (LabelFrame) -> Unit,
    outcome: () -> String?,
): ScanCamera {
    val context = LocalContext.current
    val vision = LocalLabelVision.current
    val camera = remember(vision) {
        AndroidScanCamera(
            context = context,
            vision = vision,
            granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    camera.reading = rememberUpdatedState(reading)
    camera.onBarcode = rememberUpdatedState(onBarcode)
    camera.onFrame = rememberUpdatedState(onFrame)
    camera.outcome = rememberUpdatedState(outcome)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        camera.granted = allowed
        camera.answered = true
    }
    LaunchedEffect(Unit) {
        if (!camera.granted) permission.launch(Manifest.permission.CAMERA)
    }
    return camera
}

private class AndroidScanCamera(
    private val context: Context,
    vision: LabelVision?,
    granted: Boolean,
) : ScanCamera {

    override var granted by mutableStateOf(granted)

    // Пока не спросили, показывать «доступа нет» рано: человек ещё не отвечал.
    override var answered by mutableStateOf(granted)

    lateinit var reading: State<() -> Boolean>
    lateinit var onBarcode: State<(String) -> Unit>
    lateinit var onFrame: State<(LabelFrame) -> Unit>
    lateinit var outcome: State<() -> String?>

    /** Последний резкий кадр этикетки: его читает нейросеть, когда камера застряла. */
    private val latest = AtomicReference<Bitmap?>(null)

    override val secondOpinion: (suspend () -> ProductDraft?)? =
        vision?.let { reader -> suspend { latest.get()?.let { frame -> reader.read(frame) } } }

    @Composable
    override fun Preview(modifier: Modifier, zoom: Float, onPinch: ((Float) -> Unit)?, fit: Float) {
        CameraFrames(
            modifier = modifier,
            zoom = zoom,
            onPinch = onPinch,
            alignment = BiasAlignment(0f, -fit),
            contentScale = CameraFit(fit),
        ) {
            scanAnalysis(
                context = context,
                reading = { reading.value() },
                onBarcode = { code -> onBarcode.value(code) },
                onFrame = { frame -> onFrame.value(frame) },
                outcome = { outcome.value() },
                latest = latest,
            )
        }
    }
}

/**
 * Как кадр ложится на экран.
 *
 * 0 — на весь экран с обрезкой краёв, как у штрих-кода: панели нет, и пустая
 * полоса под кадром была бы ни к чему. 1 — по ширине от верха, как у этикетки:
 * то, что видит человек, и то, что уходит в распознавание, — одна и та же картинка,
 * а низ закрывает карточка. Между ними — плавно: вьюфайндер масштабирует слой
 * поверх поверхности камеры, и сама поверхность не пересоздаётся.
 */
private data class CameraFit(val fit: Float) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val crop = ContentScale.Crop.computeScaleFactor(srcSize, dstSize)
        val byWidth = ContentScale.FillWidth.computeScaleFactor(srcSize, dstSize)
        return ScaleFactor(lerpFloat(crop.scaleX, byWidth.scaleX, fit), lerpFloat(crop.scaleY, byWidth.scaleY, fit))
    }
}
