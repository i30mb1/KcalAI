package n7.kcalai.feature.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Process
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import n7.kcalai.model.Nutriments
import n7.kcalai.ui.KcalChip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import n7.kcalai.ocr.LabelOcr
import n7.kcalai.repositories.LabelReading
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.Macro
import n7.kcalai.ui.WideButton
import n7.kcalai.ui.colors

/**
 * Карточка продукта, которую заполняет камера.
 *
 * Раньше здесь был сканер, после которого открывалась форма на пять полей: камера
 * отдавала числа, человек смотрел на них уже на другом экране и там же правил.
 * Два экрана на одно действие — и оба неполные: на первом ничего нельзя было
 * поправить, на втором не было видно пачки.
 *
 * Теперь экран один. Камера набирает значения кадр за кадром, любое из них можно
 * снять крестиком и набрать своё, а пачка всё это время в кадре — сверять есть
 * с чем. Без камеры экран остаётся той же карточкой, просто пустой: заполнить
 * её руками можно всегда.
 *
 * @param gtin код, с промаха которого сюда пришли. Камера ловит и свой — см.
 *        [LabelAnalyzer], — но пришедший извне сильнее: его человек отсканировал
 *        намеренно.
 * @param onSave отдаёт готовый продукт наружу. Экран сам ничего не сохраняет:
 *        куда класть продукт, решает дневник.
 */
@Composable
fun LabelScannerDialog(
    onSave: (gtin: String?, name: String, nutriments: Nutriments, servingG: Int?) -> Unit,
    onDismiss: () -> Unit,
    gtin: String? = null,
) {
    val context = LocalContext.current
    val state = rememberScanState(gtin)

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
        Surface(Modifier.fillMaxSize(), color = KcalTheme.colors.bg) {
            Column(Modifier.fillMaxSize()) {
                // Высота окна камеры — доля экрана, а не остаток от панели.
                // Панель растёт и сжимается постоянно: пришли чипсы имён, встали
                // числа с этикетки, ушла подсказка. Будь камера остатком, каждое
                // такое изменение меняло бы размер превью, а `PreviewView` под
                // капотом `SurfaceView`: смена размера — это пересоздание surface
                // и переконфигурация сессии, то есть заметный рывок картинки
                // ровно в тот момент, когда человек наводит на пачку.
                Box(Modifier.fillMaxWidth().fillMaxHeight(CAMERA_SHARE)) {
                    if (granted) {
                        CameraFeed(state)
                        Viewfinder(Modifier.fillMaxSize())
                    }

                    ScanTopBar(
                        title = "новый продукт",
                        meta = if (state.seen > 0) {
                            "кадр ${state.seen} · ${state.frame.elapsedMs} мс"
                        } else {
                            null
                        },
                        onDismiss = onDismiss,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    // Подсказка живёт, пока не прочитан первый кадр. Дальше о том же
                    // говорят полоски, и держать её значило бы объяснять очевидное.
                    val hint = when {
                        !granted && answered -> "Камеры нет — заполните поля сами"
                        !granted -> "Запрашиваем доступ к камере"
                        !state.frame.available -> "Распознавание недоступно — заполните сами"
                        state.seen == 0 -> "Держите таблицу пищевой ценности в рамке"
                        else -> null
                    }
                    if (hint != null) {
                        HintPill(
                            text = hint,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 32.dp, vertical = 20.dp),
                        )
                    }
                }

                ProductCard(
                    state = state,
                    modifier = Modifier.weight(1f),
                    onSave = {
                        state.onSaved()
                        onSave(state.gtin, state.name.text.trim(), it, state.servingG)
                    },
                )
            }
        }
    }
}

/** Превью и распознавание. Всё, что оно находит, уходит в [state]. */
@Composable
private fun CameraFeed(state: ScanState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }

    DisposableEffect(lifecycleOwner) {
        // Разбор кадра целиком уходит на потоки пониженного приоритета, и это
        // не микрооптимизация. Распознавание занимает сотни миллисекунд и грузит
        // все ядра; с обычным приоритетом оно конкурирует за процессор с отрисовкой
        // превью, и камера начинает дёргаться ровно тогда, когда человек наводит.
        // Кадром позже разбор ничего не теряет, дёрганое превью — теряет наводку.
        val executor = Executors.newSingleThreadExecutor(unhurried("label-frame"))
        val inference = Executors.newSingleThreadExecutor(unhurried("label-ocr"))
        val dispatcher = inference.asCoroutineDispatcher()

        val ocr = LabelOcr(context, dispatcher)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val recorder = LabelRecorder(LabelRecorder.directory(context))
        val startedAt = System.currentTimeMillis()
        val main = ContextCompat.getMainExecutor(context)

        val analyzer = LabelAnalyzer(
            ocr = ocr,
            scope = scope,
            recorder = recorder,
            onBarcode = { code -> main.execute { state.onBarcode(code) } },
            onFrame = { read -> main.execute { state.onFrame(read) } },
        )

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
            // Нативные сессии надо отпустить: они держат модели в памяти.
            ocr.close()
            analyzer.close()
            // Запись — уже после остановки анализа и не на этом потоке: там
            // несколько мегабайт, а мы на главном. Свой поток, а не executor
            // анализа: тот сейчас закрывается.
            //
            // Итог снимается здесь же: к моменту закрытия экрана поля уже
            // содержат то, с чем человек согласился, — а расхождение с тем,
            // что предлагала камера, и есть материал для правки разбора.
            val outcome = state.outcome(state.saved)
            Thread { recorder.save(startedAt, outcome) }.start()
            executor.shutdown()
            inference.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                this.controller = controller
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
    )
}

/**
 * Карточка продукта: шесть полей, полоски набранного согласия и сохранение.
 *
 * Полоска показывает не «идёт загрузка», а согласие кадров: значение встаёт
 * в поле, только когда несколько кадров подряд прочитали одно и то же. Видеть
 * надо именно **какое** поле не набралось — это подсказывает действие: подвинуть
 * камеру, убрать блик с той самой строки, поднести ближе.
 */
@Composable
private fun ProductCard(
    state: ScanState,
    onSave: (Nutriments) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KcalTheme.colors
    val check = state.check

    /** Куда подставит число тап по чипсу. Живёт дольше фокуса — см. [ScannedNumbers]. */
    var focused by remember { mutableStateOf<ScanField?>(null) }

    ScanPanel(modifier.navigationBarsPadding().imePadding()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(state.overall)

                Column(Modifier.padding(start = 16.dp)) {
                    NameField(state.name) { focused = null }
                    CapsLabel(
                        "${state.filledFields} из 6 полей · обязательных ${state.requiredFilled} / 4",
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // Названия с пачки: первое подставляется само, остальные ждут тапа.
            // Угадывать молча тут нельзя — человек всё равно смотрит на пачку,
            // и дать ему выбор честнее, чем настаивать на догадке.
            NameChoices(state)

            Spacer(Modifier.size(16.dp))

            ScanEditRow(
                label = "калории",
                value = state.kcal.text,
                placeholder = "${(state.progress("ккал") * 100).toInt()}%",
                fraction = state.progress("ккал"),
                // Калории — не макрос, своего цвета у них нет: они складываются
                // из всех трёх. Поэтому цвет чернил, как у остатка в дневнике.
                color = colors.text,
                onValueChange = { state.kcal.type(it.filter(Char::isDigit)) },
                onClear = { state.kcal.clear() },
                onFocus = { if (it) focused = state.kcal },
                // Единицы у калорий не подписаны: подпись слева уже сказала
                // «калории», а четырёхзначное число и «ккал» рядом не помещаются.
            )
            MacroRow("белки", state.prot, "Б", Macro.PROTEIN, state) { focused = it }
            MacroRow("жиры", state.fat, "Ж", Macro.FAT, state) { focused = it }
            MacroRow("углеводы", state.carb, "У", Macro.CARB, state) { focused = it }

            HorizontalDivider(color = colors.line, modifier = Modifier.padding(vertical = 14.dp))

            ScanEditRow(
                label = "порция",
                value = state.serving.text,
                placeholder = "—",
                fraction = if (state.serving.filled) 1f else 0f,
                color = colors.text3,
                onValueChange = { state.serving.type(it.filter(Char::isDigit)) },
                onClear = { state.serving.clear() },
                onFocus = { if (it) focused = state.serving },
                optional = true,
                unit = "г",
            )
            Spacer(Modifier.size(10.dp))
            ScanEditRow(
                label = "штрихкод",
                value = state.barcode.text,
                placeholder = "—",
                // Код на этом экране ловится редко: камера смотрит на таблицу,
                // а код напечатан с другой стороны пачки. Полоска поэтому
                // двоичная — поймали или нет, — и поле можно набрать руками.
                fraction = if (state.gtin != null) 1f else 0f,
                color = colors.text3,
                onValueChange = { state.barcode.type(it.filter(Char::isDigit).take(MAX_GTIN_DIGITS)) },
                onClear = { state.barcode.clear() },
                onFocus = { if (it) focused = state.barcode },
                optional = true,
            )

            ScannedNumbers(state, focused)

            // Ошибка гасит кнопку, замечание — нет. Расходящиеся цифры бывают
            // напечатаны на реальной упаковке, и спорить с упаковкой мы не вправе.
            check?.error?.let { Note(it, colors.error) }
            check?.warning?.let { Note(it, colors.text2) }
            if (state.barcode.filled && state.gtin == null) {
                Note("Код не сходится по контрольной цифре — проверьте цифры", colors.error)
            }

            // Молчать о том, почему кнопка серая, нельзя: человек решит, что
            // сломано, и уйдёт — ровно на последнем шаге.
            if (!state.canSave && check?.error == null) {
                Note(
                    when {
                        state.name.text.isBlank() ->
                            "Назовите продукт — без названия его потом не найти"
                        else -> "Нужны калории на 100 г: их с пачки ждут все остальные числа"
                    },
                    colors.text3,
                )
            }

            WideButton(
                text = "Сохранить",
                enabled = state.canSave,
                onClick = { state.nutriments?.let(onSave) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            )
        }
    }
}

/** Название — то единственное, что распознаватель угадывает, а человек знает. */
@Composable
private fun NameField(field: ScanField, onFocused: () -> Unit) {
    val colors = KcalTheme.colors

    Box {
        BasicTextField(
            value = field.text,
            onValueChange = field::type,
            textStyle = KcalTheme.type.title.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFocused() },
        )
        if (field.text.isEmpty()) {
            Text("Название с пачки", style = KcalTheme.type.title, color = colors.text3)
        }
    }
}

/** Варианты названия, набравшие голоса кадров. Тап подставляет в поле. */
@Composable
private fun NameChoices(state: ScanState) {
    val choices = state.nameChoices.filter { it != state.name.text }
    if (choices.isEmpty()) return

    LazyRow(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(choices.size) { index ->
            val choice = choices[index]
            KcalChip(onClick = { state.name.type(choice) }, background = KcalTheme.colors.chip) {
                Text(
                    choice,
                    style = KcalTheme.type.chip,
                    color = KcalTheme.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Макрос: та же строка, но подписью и полоской своего цвета. */
@Composable
private fun MacroRow(
    label: String,
    field: ScanField,
    key: String,
    macro: Macro,
    state: ScanState,
    onFocus: (ScanField?) -> Unit,
) {
    Spacer(Modifier.size(11.dp))
    ScanEditRow(
        label = label,
        value = field.text,
        placeholder = "${(state.progress(key) * 100).toInt()}%",
        fraction = state.progress(key),
        color = macro.colors().fill,
        onValueChange = { field.type(it.filter { c -> c.isDigit() || c == ',' || c == '.' }) },
        onClear = { field.clear() },
        onFocus = { if (it) onFocus(field) },
        decimal = true,
        unit = "г",
    )
}

/**
 * Числа, которые распознались, но которые разбор не разложил сам.
 *
 * Так выглядит честный отказ: приложение не угадывает, куда поставить цифру,
 * а показывает всё, что увидело, и отдаёт решение человеку. Четыре тапа —
 * всё равно несопоставимо быстрее, чем набрать «12,4» на мобильной клавиатуре.
 *
 * Фокус именно запоминается, а не читается в момент тапа: нажатие на чипс
 * снимает фокус с поля, и спрашивать о нём было бы уже поздно.
 */
@Composable
private fun ScannedNumbers(state: ScanState, focused: ScanField?) {
    val numbers = state.numbers.distinct()
    if (numbers.isEmpty() || focused == null) return

    Column(Modifier.padding(top = 14.dp)) {
        CapsLabel("распознано с этикетки")
        LazyRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(numbers.size) { index ->
                val value = numbers[index]
                KcalChip(
                    onClick = { focused.type(value) },
                    background = KcalTheme.colors.chip,
                ) {
                    Text(value, style = KcalTheme.type.chip, color = KcalTheme.colors.text)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(
        text,
        style = KcalTheme.type.body,
        color = color,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/**
 * Съёмка этикетки вхолостую — чтобы посмотреть, что вообще читается.
 *
 * Обычный путь к распознаванию идёт через промах штрих-кода, и проверить разбор
 * на конкретной пачке значит каждый раз найти товар, которого нет в базе. Здесь
 * то же распознавание запускается сразу и показывает себя целиком: строки,
 * маршрут разбора, что принято за ноль. В дневник отсюда не попадает ничего.
 */
@Composable
fun LabelDebugDialog(onRead: (LabelReading) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberScanState(null)
    val deliver by rememberUpdatedState(onRead)

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed -> granted = allowed }

    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = KcalTheme.colors.bg) {
            Box(Modifier.fillMaxSize()) {
                if (granted) CameraFeed(state)

                DebugPane(
                    frame = state.frame,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(start = 8.dp, end = 8.dp, top = 56.dp)
                        .fillMaxHeight(0.8f),
                )

                ScanTopBar(
                    title = "разбор этикетки",
                    meta = "кадр ${state.seen}",
                    onDismiss = onDismiss,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                ScanActions(
                    primary = "В лог",
                    primaryEnabled = true,
                    onPrimary = { deliver(state.frame.reading) },
                    secondary = "Закрыть",
                    onSecondary = onDismiss,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp),
                )
            }
        }
    }
}

/**
 * Поток, который уступает дорогу интерфейсу.
 *
 * Приоритет ниже обычного, но выше «фонового»: `THREAD_PRIORITY_BACKGROUND`
 * отправляет поток в background-cgroup, а там система выдаёт ему считанные
 * проценты процессора — распознавание кадра растянулось бы на секунды.
 * Нужна ровно уступка отрисовке, а не ссылка в каменоломню.
 */
private fun unhurried(name: String) = ThreadFactory { runnable ->
    Thread({
        Process.setThreadPriority(FRAME_THREAD_PRIORITY)
        runnable.run()
    }, name)
}

/** Ниже интерфейсного нуля, выше фоновой десятки. */
private const val FRAME_THREAD_PRIORITY = 4

/**
 * Какую долю экрана занимает камера.
 *
 * Доля, а не остаток: см. комментарий в разметке. Сорок два процента — окно
 * видоискателя целиком плюс воздух вокруг него, дальше начинается панель.
 */
private const val CAMERA_SHARE = 0.42f

/** EAN-13 — самый длинный из форматов, которые встречаются на еде. */
private const val MAX_GTIN_DIGITS = 13

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
        Box(Modifier.width(72.dp).height(4.dp).background(Color.White.copy(alpha = 0.2f))) {
            Box(
                Modifier
                    .fillMaxWidth(field.progress)
                    .height(4.dp)
                    .background(if (field.settled) Color(0xFFB9F6CA) else Color(0xFFFFE082))
            )
        }
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
