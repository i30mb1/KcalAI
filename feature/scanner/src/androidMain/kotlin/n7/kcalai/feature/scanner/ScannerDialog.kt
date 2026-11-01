package n7.kcalai.feature.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import n7.kcalai.model.Nutriments
import n7.kcalai.ui.KcalTheme
import androidx.compose.ui.util.lerp as lerpFloat

/**
 * Окно съёмки — от штрих-кода до карточки нового продукта.
 *
 * Раньше их было два: сканер кода закрывался, как только код прочитан, на время
 * поиска показывался дневник, а промах открывал второе окно со своей камерой —
 * экран мигал, камера запускалась заново, пачка уходила из кадра. Теперь окно
 * одно, и шаги сменяют друг друга в нём же:
 *
 * - код: камера на весь экран, узкая рамка под штрих-код;
 * - поиск: та же картинка, рамка зеленеет, внизу «Ищу товар…»;
 * - новый продукт: камера плавно приближается к таблице и рамка вытягивается
 *   под неё, сверху выходят трое — каждый просит своё, — снизу выезжает карточка.
 *
 * Отказ в доступе к камере — не тупик, а вторая дверь: цифры набираются кнопками.
 */
@Composable
actual fun ScannerDialog(
    step: ScanStep,
    onScanned: (String) -> Unit,
    onSave: (gtin: String?, name: String, nutriments: Nutriments) -> Unit,
    onDismiss: () -> Unit,
    frameStats: Boolean,
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
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        granted = allowed
        answered = true
    }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }

    val label = step is ScanStep.NewProduct
    val searching = step is ScanStep.Searching
    val card = (step as? ScanStep.NewProduct)?.let { rememberScanState(it.gtin) }
    var typingCode by rememberSaveable { mutableStateOf(false) }

    if (card != null) {
        CardClock(card)
        ScanHaptics(card)
        LaunchedEffect(card, answered, granted) {
            if (answered && !granted) card.withoutCamera()
        }
    }
    CaughtHaptic(searching)

    // Код уходит наружу один раз: камера читает его десятки раз подряд, а набранный
    // руками и пойманный камерой в ту же секунду — это всё ещё один товар.
    val delivered = remember { AtomicBoolean(false) }
    val submit by rememberUpdatedState { code: String ->
        if (delivered.compareAndSet(false, true)) onScanned(code)
    }

    // Анализ кадров собирается один раз за окно; шаг и карточка меняются у него
    // на глазах, поэтому он читает их свежими, а не те, что были при сборке.
    val currentStep by rememberUpdatedState(step)
    val currentCard by rememberUpdatedState(card)
    val reading = remember { AtomicBoolean(label) }
    SideEffect { reading.set(label) }

    // Заполненную карточку крестик и «назад» не закрывают молча: камера минуту
    // набирала голоса, человек дописывал, и всё это уходило одним касанием.
    var confirmClose by remember { mutableStateOf(false) }
    val requestClose: () -> Unit = {
        if (card?.hasDraft == true) confirmClose = true else onDismiss()
    }
    // «Назад» сначала прячет цифры — потом закрывает окно.
    val back: () -> Unit = {
        when {
            card?.typing != null -> card.stopTyping()
            typingCode && granted -> typingCode = false
            else -> requestClose()
        }
    }

    // 0 — кадр на весь экран, как у штрих-кода; 1 — по ширине от верха, как у этикетки.
    val fit = remember { Animatable(if (label) 1f else 0f) }
    LaunchedEffect(label) {
        fit.animateTo(if (label) 1f else 0f, tween(FIT_MS, easing = FastOutSlowInEasing))
    }
    var labelZoom by rememberSaveable { mutableFloatStateOf(LABEL_ZOOM) }

    ScanDialog(onDismiss = back, onCamera = granted) {
        Surface(Modifier.fillMaxSize(), color = if (granted) CAMERA_BACKDROP else KcalTheme.colors.bg) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val t = fit.value
                val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val shelfTop = statusTop + TOP_BAR_HEIGHT
                val window = viewfinderWindow(maxWidth, maxHeight, shelfTop, t)

                if (granted) {
                    CameraFrames(
                        modifier = Modifier.pointerInput(Unit) {
                            // Тап по камере прячет цифры: человек хочет ещё раз камерой.
                            detectTapGestures {
                                currentCard?.stopTyping()
                                typingCode = false
                            }
                        },
                        // Приближение растёт вместе с переходом к этикетке, а не скачком:
                        // скачок зума посреди движения рамки читался бы как сбой.
                        zoom = 1f + (labelZoom - 1f) * t,
                        onPinch = if (label) { factor -> labelZoom = pinchedZoom(labelZoom, factor) } else null,
                        alignment = BiasAlignment(0f, -t),
                        contentScale = CameraFit(t),
                    ) {
                        scanAnalysis(
                            context = context,
                            reading = reading::get,
                            onBarcode = { code ->
                                when (currentStep) {
                                    ScanStep.Barcode -> submit(code)
                                    is ScanStep.Searching -> Unit
                                    is ScanStep.NewProduct -> currentCard?.onBarcode(code)
                                }
                            },
                            onFrame = { frame -> currentCard?.onFrame(frame) },
                            outcome = { currentCard?.let { it.outcome(it.saved) } },
                        )
                    }
                    Viewfinder(top = window.top, height = window.height, caught = searching)
                }

                ScanTopBar(
                    title = if (label) "новый продукт" else "штрих-код",
                    meta = card?.takeIf { frameStats && it.seen > 0 }
                        ?.let { "кадр ${it.seen} · ${it.frame.elapsedMs} мс" },
                    onDismiss = requestClose,
                    modifier = Modifier.align(Alignment.TopCenter),
                    onCamera = granted,
                    action = when {
                        label && granted -> {
                            { ZoomPill(labelZoom) { labelZoom = nextZoomStop(labelZoom) } }
                        }
                        // Ручной ввод рядом с крестиком, а не вместо камеры: смазанный код
                        // на мятой пачке не читается никогда, и цифры под ним — рабочий путь.
                        step == ScanStep.Barcode && granted && !typingCode -> {
                            { CodeButton { typingCode = true } }
                        }
                        else -> null
                    },
                )

                if (card != null) {
                    MascotShelf(
                        lines = card.mascotLines(),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = shelfTop),
                    )
                }

                val bottom: Bottom = when {
                    card != null -> Bottom.Card(card)
                    typingCode || (answered && !granted) -> Bottom.Code(searching)
                    else -> Bottom.Hint(searching)
                }
                // Кадр 3:4 стоит по ширине от верха — ниже него только панель,
                // и она обязана закрыть всё, что ниже кадра.
                val cardMax = (maxHeight - (shelfTop + SHELF_HEIGHT + 8.dp)).coerceAtLeast(0.dp)
                val cardMin = if (granted) (maxHeight - maxWidth * 4f / 3f).coerceIn(0.dp, cardMax) else 0.dp

                AnimatedContent(
                    targetState = bottom,
                    contentKey = { it::class },
                    transitionSpec = {
                        (slideInVertically(tween(PANEL_MS)) { it / 2 } + fadeIn(tween(PANEL_MS))) togetherWith
                            (slideOutVertically(tween(PANEL_MS / 2)) { it / 2 } + fadeOut(tween(PANEL_MS / 2))) using
                            SizeTransform(clip = false)
                    },
                    contentAlignment = Alignment.BottomCenter,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    label = "panel",
                ) { shown ->
                    when (shown) {
                        is Bottom.Hint -> Box(
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            HintPill(
                                text = when {
                                    shown.searching -> "Ищу товар…"
                                    !answered -> "Запрашиваем доступ к камере…"
                                    else -> "Наведите на штрих-код"
                                },
                                busy = shown.searching,
                            )
                        }

                        is Bottom.Code -> CodeEntry(
                            title = if (granted) {
                                "Наберите цифры под штрих-кодом"
                            } else {
                                "Доступ к камере не разрешён — наберите цифры под штрих-кодом"
                            },
                            searching = shown.searching,
                            onSubmit = { code -> submit(code) },
                        )

                        is Bottom.Card -> ProductCard(
                            state = shown.state,
                            hint = panelHint(
                                camera = granted,
                                answered = answered,
                                ocr = shown.state.frame.available,
                                manual = shown.state.manual,
                                numbersMissing = shown.state.missing.isNotEmpty(),
                                nameMissing = shown.state.name.text.isBlank(),
                            ),
                            onSave = { nutriments ->
                                shown.state.onSaved()
                                onSave(shown.state.gtin, shown.state.name.text.trim(), nutriments)
                            },
                            modifier = Modifier.heightIn(min = cardMin, max = cardMax),
                        )
                    }
                }
            }
        }

        if (confirmClose) {
            DiscardDraftDialog(
                onDiscard = {
                    confirmClose = false
                    onDismiss()
                },
                onKeep = { confirmClose = false },
            )
        }
    }
}

/** Что стоит внизу окна. Анимируется смена вида, а не каждое изменение внутри. */
private sealed interface Bottom {
    data class Hint(val searching: Boolean) : Bottom
    data class Code(val searching: Boolean) : Bottom
    data class Card(val state: ScanState) : Bottom
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

private class Window(val top: Dp, val height: Dp)

/**
 * Где окно видоискателя: полоса под штрих-код (fit = 0) или окно под таблицу (fit = 1).
 *
 * Полоса — чуть выше середины экрана: штрих-код узкий, а не таблица. Окно под
 * таблицу — от персонажей до низа кадра: кадр стоит по ширине от верха, и ниже
 * него только карточка.
 */
private fun viewfinderWindow(width: Dp, height: Dp, shelfTop: Dp, fit: Float): Window {
    val codeHeight = height * CODE_WINDOW_SHARE
    val codeTop = (height - codeHeight) * 0.42f

    val tableTop = shelfTop + SHELF_HEIGHT + 10.dp
    val tableBottom = minOf(width * 4f / 3f, height - READING_PANEL_HEIGHT) - 12.dp
    val tableHeight = (tableBottom - tableTop).coerceAtLeast(MIN_WINDOW)

    return Window(top = lerp(codeTop, tableTop, fit), height = lerp(codeHeight, tableHeight, fit))
}

/** Раз в секунду — не пора ли просить вписать руками. Не анимация: ничего не перерисовывает. */
@Composable
private fun CardClock(card: ScanState) {
    LaunchedEffect(card) {
        while (true) {
            delay(TICK_MS)
            card.tick()
        }
    }
}

/** Код пойман — ладонь это чувствует: человек смотрит на пачку, а не на экран. */
@Composable
private fun CaughtHaptic(caught: Boolean) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(caught) {
        if (caught) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }
}

/**
 * Отклик ладони на то, что происходит на экране.
 *
 * Человек смотрит на пачку, а не на экран: он держит камеру над строчками
 * и ждёт. Экран показывает, как заполняются поля и когда четыре числа
 * сошлись, но глаза в этот момент не на нём — а ладонь чувствует.
 *
 * Два уровня. Едва заметный тик — камера заполнила ещё одно поле; этого
 * достаточно, чтобы знать «идёт», не глядя. Мягкое подтверждение — таблица
 * сошлась целиком. Когда четвёрка сходится тем же кадром, что и последнее
 * поле, тика нет: подтверждение его перекрывает, и два толчка подряд
 * читались бы как сбой. Считаются только поля, заполненные камерой:
 * набранное руками ладонь и так знает.
 */
@Composable
private fun ScanHaptics(state: ScanState) {
    val haptic = LocalHapticFeedback.current
    val confident = state.frame.reading.confident
    LaunchedEffect(confident) {
        if (confident) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    val filled = state.cameraFilled
    var seen by remember { mutableIntStateOf(filled) }
    LaunchedEffect(filled) {
        if (filled > seen && !confident) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        seen = filled
    }
}

/** «Ввести код» рядом с крестиком. */
@Composable
private fun CodeButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(KcalTheme.colors.surface.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text("Ввести код", style = KcalTheme.type.label, color = KcalTheme.colors.text)
    }
}

/** Верхняя строка: крестик 44 dp и отступы вокруг. Персонажи стоят сразу под ней. */
private val TOP_BAR_HEIGHT = 56.dp

/** Высота полосы под штрих-код — доля экрана. Код — узкая полоса, а не таблица. */
private const val CODE_WINDOW_SHARE = 0.22f

/** Сколько обычно занимает карточка, пока читает камера: окно под таблицу — выше неё. */
private val READING_PANEL_HEIGHT = 300.dp

/** Меньше окно не бывает даже на самом низком экране: в него должна влезать строка. */
private val MIN_WINDOW = 96.dp

/** Переход от кода к этикетке: камера, рамка и приближение — одним движением. */
private const val FIT_MS = 650

private const val PANEL_MS = 320

private const val TICK_MS = 1_000L
