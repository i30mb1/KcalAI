package n7.kcalai.feature.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import n7.kcalai.repositories.BarcodeValidator
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.WideButton

/**
 * Скан штрих-кода поверх дневника.
 *
 * Отказ в доступе к камере — не тупик, а вторая дверь: открывается ручной ввод цифр
 * с упаковки. Это не уступка ради галочки, а рабочий сценарий — так же проверяется
 * вся сетевая цепочка на эмуляторе, где камеру не на что навести.
 *
 * @param onScanned вызывается ровно один раз с уже нормализованным до EAN-13 кодом.
 */
@Composable
fun ScannerDialog(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // Пока не спросили, показывать «доступа нет» рано: человек ещё не отвечал.
    var answered by remember { mutableStateOf(granted) }
    var manual by remember { mutableStateOf(false) }

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
        Surface(Modifier.fillMaxSize(), color = KcalTheme.colors.bg) {
            Box(Modifier.fillMaxSize()) {
                when {
                    granted && !manual -> CameraPane(onScanned)
                    answered -> ManualEntry(
                        hint = if (granted) {
                            "Введите цифры под штрих-кодом"
                        } else {
                            "Доступ к камере не разрешён — введите цифры под штрих-кодом"
                        },
                        onSubmit = onScanned,
                    )
                    else -> WaitingForPermission()
                }

                ScanTopBar(
                    title = "штрих-код",
                    meta = null,
                    onDismiss = onDismiss,
                    modifier = Modifier.align(Alignment.TopCenter),
                    action = if (granted && !manual) {
                        {
                            // Ручной ввод рядом с крестиком, а не вместо камеры:
                            // смазанный код на мятой пачке не читается никогда,
                            // и цифры под ним — рабочий путь, а не аварийный.
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(KcalTheme.colors.surface.copy(alpha = 0.92f))
                                    .clickable { manual = true }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    "Ввести код",
                                    style = KcalTheme.type.label,
                                    color = KcalTheme.colors.text,
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun CameraPane(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }

    /**
     * Распознавание идёт на каждом кадре, и один и тот же код прилетает подряд
     * десятки раз. Защёлка вместо флага состояния — потому что срабатывает она
     * на потоке анализа, а не в композиции.
     */
    val delivered = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val analyzer = BarcodeAnalyzer { code ->
            if (delivered.compareAndSet(false, true)) {
                ContextCompat.getMainExecutor(context).execute { onScanned(code) }
            }
        }

        // Съёмка и видео не нужны: без них не создаются их use case'ы,
        // а камера стартует заметно быстрее.
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisAnalyzer(executor, analyzer)
        controller.bindToLifecycle(lifecycleOwner)

        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analyzer.close()
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

        // Рамка не участвует в распознавании — ML Kit смотрит весь кадр.
        // Она нужна человеку: без неё непонятно, куда наводить. Окно ниже
        // и уже, чем у этикетки: штрих-код — узкая полоса, а не таблица.
        Viewfinder(Modifier.fillMaxSize(), windowHeight = 0.22f)

        HintPill(
            text = "Наведите на штрих-код",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(32.dp),
        )
    }
}

@Composable
private fun WaitingForPermission() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Запрашиваем доступ к камере…",
            style = KcalTheme.type.body,
            color = KcalTheme.colors.text2,
        )
    }
}

/**
 * Ручной ввод кода.
 *
 * Кнопка включается только на коде с верной контрольной цифрой: опечатка в одной
 * цифре увела бы человека заводить продукт под несуществующим номером, и найти
 * его потом не смог бы никто, включая его самого.
 */
@Composable
private fun ManualEntry(hint: String, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    val normalized = BarcodeValidator.normalize(value)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(hint, style = KcalTheme.type.title, color = KcalTheme.colors.text)
        Spacer(Modifier.size(16.dp))

        OutlinedTextField(
            value = value,
            onValueChange = { text -> value = text.filter(Char::isDigit).take(MAX_GTIN_DIGITS) },
            label = { Text("Штрих-код") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            // Молчать о том, почему кнопка серая, нельзя: человек решит, что сломано.
            when {
                value.isEmpty() -> "8, 12 или 13 цифр"
                normalized == null -> "Код не сходится — проверьте цифры"
                else -> "Код верный"
            },
            style = KcalTheme.type.time,
            color = if (value.isNotEmpty() && normalized == null) {
                KcalTheme.colors.error
            } else {
                KcalTheme.colors.text3
            },
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        )

        Spacer(Modifier.size(16.dp))
        WideButton(
            text = "Найти",
            enabled = normalized != null,
            onClick = { normalized?.let(onSubmit) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** EAN-13 — самый длинный из форматов, которые встречаются на еде. */
private const val MAX_GTIN_DIGITS = 13
