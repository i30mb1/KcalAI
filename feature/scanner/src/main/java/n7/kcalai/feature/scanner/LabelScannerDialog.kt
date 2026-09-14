package n7.kcalai.feature.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import n7.kcalai.ocr.LabelOcr
import n7.kcalai.repositories.LabelReading

/**
 * Съёмка таблицы пищевой ценности.
 *
 * Заполнять форму нового продукта руками — пять значений с мобильной клавиатуры,
 * три из них дробные. Это то место, где люди бросают приложение, и здесь оно
 * заменяется наведением камеры на пачку.
 *
 * Ведёт себя как сканер штрих-кода: распознавание идёт непрерывно, и как только
 * числа сошлись между собой, диалог закрывается сам. Разница одна — у этикетки
 * есть исход «прочиталось, но не сошлось», и на него нужна кнопка «Готово»:
 * она отдаёт то, что видно, и человек назначает числа сам.
 *
 * @param onRead вызывается ровно один раз — автоматически при уверенном разборе
 *        либо по кнопке.
 */
@Composable
fun LabelScannerDialog(
    onRead: (LabelReading) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var answered by remember { mutableStateOf(granted) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        granted = allowed
        answered = true
    }

    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                when {
                    granted -> CameraPane(onRead)
                    // Без камеры снимать нечего: форма остаётся, человек заполнит руками.
                    answered -> NoCamera()
                    else -> Waiting()
                }

                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть съёмку этикетки")
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPane(onRead: (LabelReading) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }

    /** Кадров в секунду десятки, а отдать результат надо один раз. */
    val delivered = remember { AtomicBoolean(false) }

    /**
     * Последнее, что удалось прочитать, — его отдаёт кнопка «Готово».
     *
     * Обновляется с потока анализа, поэтому наружу из `DisposableEffect`
     * выносится через состояние, а не через захваченную переменную.
     */
    var latest by remember { mutableStateOf(LabelReading.EMPTY) }
    val deliver by rememberUpdatedState(onRead)

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val ocr = LabelOcr(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val analyzer = LabelAnalyzer(ocr, scope) { reading ->
            ContextCompat.getMainExecutor(context).execute {
                latest = reading
                // Разбор сошёлся — дальше держать человека перед камерой незачем.
                if (reading.confident && delivered.compareAndSet(false, true)) {
                    deliver(reading)
                }
            }
        }

        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisAnalyzer(executor, analyzer)
        controller.bindToLifecycle(lifecycleOwner)

        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scope.cancel()
            // Нативную сессию надо отпустить: она держит модели в памяти.
            ocr.close()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
        )

        // Рамка в распознавании не участвует — ML Kit смотрит весь кадр.
        // Она нужна человеку: без неё непонятно, куда наводить.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.85f)
                .height(220.dp)
                .border(2.dp, Color.White, RoundedCornerShape(12.dp))
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Счётчик — единственный способ показать, что камера вообще что-то видит.
            // Без него человек смотрит в чёрный ящик и не понимает, попал он или нет.
            Text(
                when {
                    latest.numbers.isEmpty() -> "Наведите на таблицу пищевой ценности"
                    else -> "Видно чисел: ${latest.numbers.size}"
                },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Button(
                enabled = latest.numbers.isNotEmpty(),
                onClick = {
                    if (delivered.compareAndSet(false, true)) deliver(latest)
                },
            ) { Text("Готово") }
        }
    }
}

@Composable
private fun NoCamera() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Доступ к камере не разрешён", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "Снять этикетку не получится — заполните КБЖУ вручную.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Waiting() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Запрашиваем доступ к камере…", style = MaterialTheme.typography.bodyLarge)
    }
}
