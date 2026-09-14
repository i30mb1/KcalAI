package n7.kcalai.feature.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
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
 * @param debug показывать разбор целиком и не закрываться самому. Разбор этикетки
 *        иначе непрозрачен: в форму приходят четыре числа, и по ним не понять,
 *        взялись они с подписей или их подобрала арифметика. В этом режиме видно
 *        всё — распознанные строки, маршрут разбора, что принято за ноль, — и
 *        закрытия по первому же сошедшемуся кадру нет, иначе смотреть было бы
 *        не на что.
 */
@Composable
fun LabelScannerDialog(
    onRead: (LabelReading) -> Unit,
    onDismiss: () -> Unit,
    debug: Boolean = false,
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
                    granted -> CameraPane(onRead, debug)
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
private fun CameraPane(onRead: (LabelReading) -> Unit, debug: Boolean) {
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
    var frame by remember { mutableStateOf(LabelFrame(LabelReading.EMPTY, 0)) }
    val deliver by rememberUpdatedState(onRead)
    val autoClose by rememberUpdatedState(!debug)

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val ocr = LabelOcr(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = LabelRecorder(LabelRecorder.directory(context))
        val startedAt = System.currentTimeMillis()

        val analyzer = LabelAnalyzer(ocr, scope, recorder) { read ->
            ContextCompat.getMainExecutor(context).execute {
                frame = read
                // Разбор сошёлся — дальше держать человека перед камерой незачем.
                if (autoClose && read.reading.confident && delivered.compareAndSet(false, true)) {
                    deliver(read.reading)
                }
            }
        }

        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        // Разрешение анализа по умолчанию — примерно 640×480, и на таблице пищевой
        // ценности этого не хватает: подписи там набраны шестым кеглем, и в кадре
        // от буквы остаётся два-три пикселя. Детектор строку находит, распознавание
        // отдаёт по ней кашу. Просить больше 1280×960 незачем — инференс растёт
        // квадратично, а разборчивее уже не становится.
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()
        // RGBA напрямую от камеры: иначе кадр приходит в YUV, и `toBitmap()`
        // перекладывает его в цвет сам, на каждом кадре и на потоке анализа.
        controller.imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.setImageAnalysisAnalyzer(executor, analyzer)
        controller.bindToLifecycle(lifecycleOwner)

        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scope.cancel()
            // Нативную сессию надо отпустить: она держит модели в памяти.
            ocr.close()
            // Запись — уже после остановки анализа и не на этом потоке: там
            // несколько мегабайт, а мы на главном. Свой поток, а не executor
            // анализа: тот сейчас закрывается.
            Thread { recorder.save(startedAt) }.start()
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

        // Рамка в распознавании не участвует — движок смотрит весь кадр.
        // Она нужна человеку: без неё непонятно, куда наводить. В тестовом режиме
        // её нет: там весь экран занят разбором, и обводить нечего.
        if (!debug) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.85f)
                    .height(220.dp)
                    .border(2.dp, Color.White, RoundedCornerShape(12.dp))
            )
        }

        if (debug) {
            DebugPane(
                frame = frame,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 56.dp)
                    .fillMaxHeight(0.7f),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // В тестовом режиме то же самое сказано подробнее и выше по экрану.
            if (!debug) Progress(frame)

            Button(
                enabled = frame.reading.numbers.isNotEmpty(),
                onClick = {
                    if (delivered.compareAndSet(false, true)) deliver(frame.reading)
                },
            ) { Text("Готово") }
        }
    }
}

/**
 * Что уже нашли и чего ещё ждём.
 *
 * Съёмка идёт секундами: распознавание считает кадр пятую долю секунды, а каждое
 * значение должно повториться в нескольких кадрах подряд, прежде чем ему поверят.
 * Всё это время экран без объяснений выглядит зависшим, и человек либо уводит
 * камеру раньше времени, либо решает, что приложение сломалось.
 *
 * Поэтому показывается не «идёт поиск», а поимённо: калории найдены, белки
 * найдены, жиры ищем. Видно и то, что работа движется, и то, **на чём именно**
 * она застряла, — а это уже действие: подвинуть камеру, убрать блик с той самой
 * строки, поднести ближе.
 */
@Composable
private fun Progress(frame: LabelFrame) {
    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            when {
                !frame.available -> "Распознавание недоступно"
                frame.frames == 0 -> "Наведите на таблицу пищевой ценности"
                frame.reading.confident -> "Готово"
                else -> "Читаем этикетку"
            },
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )

        frame.fields.forEach { (name, field) ->
            FoundRow(name, field)
        }
    }
}

/** Одно значение: нашли или ещё ищем, и насколько близко. */
@Composable
private fun FoundRow(name: String, field: LabelConsensus.Field) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            FIELD_NAMES[name] ?: name,
            style = MaterialTheme.typography.labelLarge,
            color = if (field.settled) Color.White else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.width(88.dp),
        )
        LinearProgressIndicator(
            progress = { field.progress },
            modifier = Modifier.width(96.dp),
            color = if (field.settled) Color(0xFF69F0AE) else Color(0xFFFFD54F),
            trackColor = Color.White.copy(alpha = 0.25f),
        )
    }
}

/** Подписи для человека: в панели разбора хватает «Б» и «Ж», здесь — нет. */
private val FIELD_NAMES = mapOf(
    "ккал" to "Калории",
    "Б" to "Белки",
    "Ж" to "Жиры",
    "У" to "Углеводы",
)

/**
 * Разбор целиком, поверх превью.
 *
 * Порядок сверху вниз — от вывода к основаниям: сначала что получилось, потом
 * почему именно так, и лишь в конце исходные строки. Смотрят сюда, когда разбор
 * ошибся, а искать причину снизу вверх дольше.
 */
@Composable
private fun DebugPane(frame: LabelFrame, modifier: Modifier = Modifier) {
    val reading = frame.reading
    val trace = reading.trace

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!frame.available) {
            Line("OCR на этом устройстве недоступен", Color(0xFFFF8A80))
            return@Column
        }

        Line(
            "ккал ${reading.draft.kcal100 ?: "—"} · Б ${reading.draft.prot100.grams()} · " +
                "Ж ${reading.draft.fat100.grams()} · У ${reading.draft.carb100.grams()}",
            if (reading.confident) Color(0xFFB9F6CA) else Color.White,
        )
        Line(
            "${trace.route.title} · ${if (reading.confident) "сошлось" else "не сошлось"}",
            Color(0xFFB0BEC5),
        )
        Line("кадр ${frame.size} · ${frame.elapsedMs} мс", Color(0xFFB0BEC5))

        Spacer(Modifier.size(4.dp))

        // Уверенность по каждому значению отдельно. Общий счётчик кадров тут
        // бесполезен: съёмка стоит ровно из-за одной строки, и видеть надо,
        // из-за какой именно, а не то, что «идёт».
        Line("согласие за ${frame.frames} кадров", Color(0xFFB0BEC5))
        frame.fields.forEach { (name, field) -> VoteRow(name, field) }

        Spacer(Modifier.size(4.dp))

        Line("таблица опознана: ${if (trace.tableFound) "да" else "нет"}", Color(0xFFB0BEC5))
        if (trace.readLabels.isNotEmpty()) {
            Line("по подписям: ${trace.readLabels.joinToString(", ")}", Color(0xFFB0BEC5))
        }
        if (trace.derivedLabels.isNotEmpty()) {
            // Не прочитано, а посчитано из остальных трёх по Этуотеру. Видеть это
            // надо: число верное, но взялось оно не с пачки.
            Line("досчитано по Этуотеру: ${trace.derivedLabels.joinToString(", ")}", Color(0xFF80D8FF))
        }
        if (trace.zeroedLabels.isNotEmpty()) {
            // Тот самый случай, ради которого панель и нужна: у сахара или газировки
            // подписей «Белки» и «Жиры» на этикетке нет вовсе, и ноль там — это
            // прочитанное, а не выдуманное. Отличить одно от другого по готовой
            // форме невозможно, а здесь видно прямо.
            Line("подписи нет, принято за ноль: ${trace.zeroedLabels.joinToString(", ")}", Color(0xFFFFE082))
        }
        if (reading.numbers.isNotEmpty()) {
            Line("числа: ${reading.numbers.joinToString(" ")}", Color(0xFFB0BEC5))
        }
        if (reading.names.isNotEmpty()) {
            Line("название: ${reading.names.joinToString(" / ")}", Color(0xFFB0BEC5))
        }

        Spacer(Modifier.size(4.dp))

        Line("строк: ${trace.lines.size}", Color(0xFFB0BEC5))
        trace.lines.forEach { Line("· $it", Color.White) }
    }
}

/** Одно значение: сколько кадров за него, полоской и цифрами. */
@Composable
private fun VoteRow(name: String, field: LabelConsensus.Field) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Line(name.padEnd(5), Color(0xFFB0BEC5))
        LinearProgressIndicator(
            progress = { field.progress },
            modifier = Modifier.width(72.dp),
            color = if (field.settled) Color(0xFFB9F6CA) else Color(0xFFFFE082),
            trackColor = Color.White.copy(alpha = 0.2f),
        )
        Spacer(Modifier.size(6.dp))
        Line("${field.votes}/${field.needed}", Color(0xFFB0BEC5))
    }
}

@Composable
private fun Line(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
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
