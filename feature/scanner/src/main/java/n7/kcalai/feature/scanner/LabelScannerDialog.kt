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
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.Macro
import n7.kcalai.ui.WideButton
import n7.kcalai.ui.colors

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
    /**
     * Код, с промаха которого пришли сюда.
     *
     * Экран его не читает и прочитать не может — камера смотрит на таблицу
     * пищевой ценности, а код напечатан на другой стороне пачки. Но показать
     * его надо: код делает заведённый продукт находимым у других людей, и его
     * наличие или отсутствие человек должен видеть до сохранения, а не после.
     */
    gtin: String? = null,
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
        Surface(Modifier.fillMaxSize(), color = KcalTheme.colors.bg) {
            when {
                granted -> CameraPane(gtin, onRead, onDismiss, debug)
                // Без камеры снимать нечего: форма остаётся, человек заполнит руками.
                answered -> NoCamera(onDismiss)
                else -> Waiting(onDismiss)
            }
        }
    }
}

@Composable
private fun CameraPane(
    gtin: String?,
    onRead: (LabelReading) -> Unit,
    onDismiss: () -> Unit,
    debug: Boolean,
) {
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

    /**
     * Сколько кадров прошло через распознавание с начала съёмки.
     *
     * Не то же, что окно согласия: то держит последние восемь и стоит на восьми.
     * Здесь нужен именно растущий счётчик — он единственное на экране, что
     * доказывает, что съёмка идёт, пока ни одно значение ещё не набралось.
     */
    var seen by remember { mutableIntStateOf(0) }
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
                if (read.available) seen++
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

        // Камера и панель разбора делят экран, а не лежат друг на друге: панель
        // высокая, и всплыви она поверх превью — закрыла бы ровно тот угол кадра,
        // который человек пытается навести.
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // В тестовом режиме окна нет: там весь экран занят разбором,
                // и обводить нечего.
                if (!debug) Viewfinder(Modifier.fillMaxSize())

                ScanTopBar(
                    title = "сканирование",
                    meta = if (seen > 0) "кадр $seen · ${frame.elapsedMs} мс" else null,
                    onDismiss = onDismiss,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                if (debug) {
                    DebugPane(
                        frame = frame,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(start = 8.dp, end = 8.dp, top = 56.dp)
                            .fillMaxHeight(0.8f),
                    )
                }

                // Подсказка живёт, пока не прочитан первый кадр. Дальше о том же
                // говорят полоски, и держать её значило бы объяснять очевидное.
                if (!debug && seen == 0) {
                    HintPill(
                        text = if (frame.available) {
                            "Держите таблицу пищевой ценности в рамке"
                        } else {
                            "Распознавание на этом устройстве недоступно"
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 32.dp, vertical = 20.dp),
                    )
                }
            }

            if (!debug) {
                ScanResultPanel(
                    frame = frame,
                    gtin = gtin,
                    onDeliver = {
                        if (delivered.compareAndSet(false, true)) deliver(frame.reading)
                    },
                )
            } else {
                // В тестовом режиме панель разбора не нужна: всё то же самое
                // сказано подробнее и выше по экрану.
                Box(Modifier.fillMaxWidth().background(KcalTheme.colors.surface)) {
                    ScanActions(
                        primary = "Готово",
                        primaryEnabled = true,
                        onPrimary = {
                            if (delivered.compareAndSet(false, true)) deliver(frame.reading)
                        },
                        secondary = "Закрыть",
                        onSecondary = onDismiss,
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(16.dp),
                    )
                }
            }
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
 * Поэтому показывается не «идёт поиск», а поимённо и полосками: калории набраны,
 * белки набраны, углеводы на сорока шести процентах. Видно и то, что работа идёт,
 * и то, **на чём именно** она стоит, — а это уже действие: подвинуть камеру,
 * убрать блик с той самой строки, поднести ближе.
 *
 * Название и штрих-код стоят за разделителем и подписаны «необяз.»: без них
 * продукт сохраняется, и человек не должен ждать их заполнения.
 */
@Composable
private fun ScanResultPanel(
    frame: LabelFrame,
    gtin: String?,
    onDeliver: () -> Unit,
) {
    val colors = KcalTheme.colors
    val draft = frame.reading.draft
    val fields = frame.fields.toMap()
    val name = draft.name ?: frame.reading.names.firstOrNull()

    val required = REQUIRED.map { key -> fields[key]?.progress ?: 0f }
    val requiredDone = REQUIRED.count { key -> fields[key]?.settled == true }
    val optionalDone = listOfNotNull(name, gtin).size
    val overall = if (required.isEmpty()) 0f else required.average().toFloat()

    ScanPanel(Modifier.navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(overall)

            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    name ?: "Этикетка",
                    style = KcalTheme.type.title,
                    color = if (name != null) colors.text else colors.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CapsLabel(
                    "${requiredDone + optionalDone} из 6 полей · обязательных $requiredDone / 4",
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Spacer(Modifier.size(18.dp))

        ScanFieldRow(
            label = "калории",
            value = draft.kcal100?.takeIf { fields["ккал"]?.settled == true }?.let { "$it ккал" },
            fraction = fields["ккал"]?.progress ?: 0f,
            // Калории — не макрос, и своего цвета у них нет: они складываются
            // из всех трёх. Поэтому цвет чернил, как у остатка в шапке дневника.
            color = colors.text,
        )
        Spacer(Modifier.size(11.dp))
        MacroRow("белки", draft.prot100, fields["Б"], Macro.PROTEIN)
        Spacer(Modifier.size(11.dp))
        MacroRow("жиры", draft.fat100, fields["Ж"], Macro.FAT)
        Spacer(Modifier.size(11.dp))
        MacroRow("углеводы", draft.carb100, fields["У"], Macro.CARB)

        HorizontalDivider(
            color = colors.line,
            modifier = Modifier.padding(vertical = 14.dp),
        )

        ScanFieldRow(
            label = "название",
            value = name,
            fraction = if (name != null) 1f else 0f,
            color = colors.text3,
            optional = true,
        )
        Spacer(Modifier.size(10.dp))
        ScanFieldRow(
            label = "штрихкод",
            value = gtin,
            fraction = if (gtin != null) 1f else 0f,
            color = colors.text3,
            optional = true,
        )

        // Кнопка одна, и это не упрощение макета. Экран ничего не сохраняет сам:
        // он отдаёт разобранное в форму продукта, где человек его подтверждает.
        // «Сохранить» и «ввести вручную» — один и тот же переход, и разводить его
        // на две кнопки значило бы предложить выбор, которого нет.
        //
        // Сошедшийся разбор до кнопки обычно не доживает: экран закрывается сам.
        // Поэтому по умолчанию на ней написано то, что и происходит на деле, —
        // дальше заполнять руками, с уже подставленным тем, что успело прочитаться.
        WideButton(
            text = if (frame.reading.confident) "Сохранить" else "Заполнить вручную",
            enabled = true,
            onClick = onDeliver,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        )
    }
}

/** Макрос: то же поле, но подписью и полоской своего цвета. */
@Composable
private fun MacroRow(
    label: String,
    centigrams: Int?,
    field: LabelConsensus.Field?,
    macro: Macro,
) {
    ScanFieldRow(
        label = label,
        // Число показывается только набранное голосами. Последний кадр рисовать
        // на его месте значило бы мигать цифрами, которым сами не верим.
        value = centigrams?.takeIf { field?.settled == true }?.let { "${it.grams()} г" },
        fraction = field?.progress ?: 0f,
        color = macro.colors().fill,
    )
}

/** Ключи полей в [LabelConsensus.Verdict.fields] — обязательная четвёрка. */
private val REQUIRED = listOf("ккал", "Б", "Ж", "У")

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

@Composable
private fun NoCamera(onDismiss: () -> Unit) {
    Message(
        title = "Доступ к камере не разрешён",
        text = "Снять этикетку не получится — заполните КБЖУ вручную.",
        onDismiss = onDismiss,
    )
}

@Composable
private fun Waiting(onDismiss: () -> Unit) {
    Message(
        title = "Запрашиваем доступ к камере",
        text = "Без камеры этикетку не прочитать.",
        onDismiss = onDismiss,
    )
}

/** Экран без камеры: тот же фон и тот же крестик, чтобы выход был на месте. */
@Composable
private fun Message(title: String, text: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(KcalTheme.colors.bg)) {
        ScanTopBar(
            title = "сканирование",
            meta = null,
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = KcalTheme.type.title, color = KcalTheme.colors.text)
            Spacer(Modifier.size(8.dp))
            Text(
                text,
                style = KcalTheme.type.body,
                color = KcalTheme.colors.text2,
                textAlign = TextAlign.Center,
            )
        }
    }
}
