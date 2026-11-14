package n7.kcalai.feature.scanner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.Log
import n7.kcalai.model.Nutriments
import n7.kcalai.repositories.LabelReading
import n7.kcalai.ui.KcalTheme
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import kotlin.coroutines.resume

/**
 * Окно съёмки — от штрих-кода до карточки нового продукта.
 *
 * Устроено как на Android и из тех же частей: рамка, верхняя строка, полка
 * персонажей, карточка, раскладка и приближение — общие. Своё здесь только то,
 * чего общим не бывает: сессия камеры (AVFoundation), чтение кода (сама система)
 * и чтение этикетки (Vision).
 *
 * Отказ в доступе к камере — не тупик, а вторая дверь: цифры вписываются
 * с клавиатуры.
 *
 * Опт-ин — из-за буферов кадров: они приходят указателями, а указатели
 * в cinterop ещё помечены экспериментальными.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ScannerDialog(
    step: ScanStep,
    onScanned: (String) -> Unit,
    onNoBarcode: () -> Unit,
    onSave: (gtin: String?, name: String, nutriments: Nutriments) -> Unit,
    findByName: suspend (String) -> FoodCandidate?,
    onPickMatch: (FoodCandidate) -> Unit,
    onDismiss: () -> Unit,
    frameStats: Boolean,
) {
    var granted by remember { mutableStateOf(cameraAuthorized()) }
    // Пока не спросили, показывать «доступа нет» рано: человек ещё не отвечал.
    var answered by remember { mutableStateOf(granted) }
    LaunchedEffect(Unit) {
        if (!granted) {
            granted = requestCamera()
            answered = true
        }
    }

    val label = step is ScanStep.NewProduct
    val searching = step is ScanStep.Searching
    val card = (step as? ScanStep.NewProduct)?.let { rememberScanState(it.gtin, it.name) }
    var typingCode by rememberSaveable { mutableStateOf(false) }

    if (card != null) {
        CardClock(card)
        LabelNameMatch(card, findByName)
        LaunchedEffect(card, answered, granted) {
            if (answered && !granted) card.withoutCamera()
        }
    }

    // Код уходит наружу один раз: камера читает его десятки раз подряд, а набранный
    // руками и пойманный камерой в ту же секунду — это всё ещё один товар.
    val delivered = remember { atomic(false) }
    val submit by rememberUpdatedState { code: String ->
        if (delivered.compareAndSet(expect = false, update = true)) onScanned(code)
    }

    // Шаг и карточка меняются у анализа на глазах: он читает их свежими,
    // а не те, что были при сборке сессии.
    val currentStep by rememberUpdatedState(step)
    val currentCard by rememberUpdatedState(card)
    val reading = remember { atomic(label) }
    SideEffect { reading.value = label }

    val scope = rememberCoroutineScope()
    val session = remember(granted) {
        if (!granted) {
            null
        } else {
            val analyzer = LabelAnalyzer(
                scope = scope,
                reading = { reading.value },
                onFrame = { frame -> currentCard?.onFrame(frame) },
            )
            CameraSession(
                onBarcode = { code ->
                    when (currentStep) {
                        ScanStep.Barcode -> submit(code)
                        is ScanStep.Searching -> Unit
                        is ScanStep.NewProduct -> currentCard?.onBarcode(code)
                    }
                },
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

    // Заполненную карточку крестик и «назад» не закрывают молча: камера минуту
    // набирала голоса, человек дописывал, и всё это уходило одним касанием.
    var confirmClose by remember { mutableStateOf(false) }
    val requestClose: () -> Unit = {
        if (card?.hasDraft == true) confirmClose = true else onDismiss()
    }
    // «Назад» сначала убирает ввод — потом закрывает окно.
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
    // Приближение растёт вместе с переходом к этикетке, а не скачком: скачок
    // посреди движения рамки читался бы как сбой.
    LaunchedEffect(session, labelZoom, fit.value) {
        session?.zoom(1f + (labelZoom - 1f) * fit.value)
    }

    ScanDialog(onDismiss = back, onCamera = granted) {
        Surface(Modifier.fillMaxSize(), color = if (granted) CAMERA_BACKDROP else KcalTheme.colors.bg) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val t = fit.value
                val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val shelfTop = statusTop + TOP_BAR_HEIGHT
                // Рамка держится над панелью: обрезанная снизу, она читается как поломка.
                val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                val window = viewfinderWindow(
                    width = maxWidth,
                    height = maxHeight,
                    shelfTop = shelfTop,
                    codePanel = if (typingCode) keyboard + CODE_PANEL_HEIGHT else 0.dp,
                    cardPanel = maxOf(READING_PANEL_HEIGHT, keyboard + TYPING_PANEL_HEIGHT),
                    fit = t,
                )

                if (session != null) {
                    CameraPreview(
                        session = session,
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            // Тап по камере убирает клавиатуру: человек хочет ещё раз камерой.
                            detectTapGestures {
                                currentCard?.stopTyping()
                                typingCode = false
                            }
                        },
                    )
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
                            { PillButton("Ввести код", onClick = { typingCode = true }) }
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
                        is Bottom.Hint -> Column(
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HintPill(
                                text = when {
                                    shown.searching -> "Ищу товар…"
                                    !answered -> "Запрашиваем доступ к камере…"
                                    else -> "Наведите на штрих-код"
                                },
                                busy = shown.searching,
                            )
                            // Пока код ищется, кнопка гаснет, но место держит:
                            // подсказка над ней не прыгает.
                            val noCode by animateFloatAsState(if (shown.searching) 0f else 1f, label = "no-code")
                            PillButton(
                                text = NO_BARCODE,
                                enabled = !shown.searching,
                                onClick = onNoBarcode,
                                modifier = Modifier.alpha(noCode),
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
                            onNoCode = onNoBarcode,
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
                            onPickMatch = { candidate ->
                                shown.state.onPicked(candidate)
                                onPickMatch(candidate)
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

/**
 * Отладка разбора этикетки — инструмент под Android: там он показывает, что
 * прочёл свой движок и где разошлись кадры. Здесь читает Vision, своего движка нет,
 * и показывать нечего.
 */
@Composable
actual fun LabelDebugDialog(onRead: (LabelReading) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onDismiss() }
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

/** Название с пачки -> похожий продукт справочника. Пауза, чтобы не дёргать базу на каждый кадр. */
@Composable
private fun LabelNameMatch(card: ScanState, findByName: suspend (String) -> FoodCandidate?) {
    val query = card.nameQuery
    LaunchedEffect(card, query) {
        if (query == null) return@LaunchedEffect
        delay(MATCH_DELAY_MS)
        card.onMatch(query, findByName(query))
    }
}

private fun cameraAuthorized(): Boolean =
    AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized

private suspend fun requestCamera(): Boolean = suspendCancellableCoroutine { continuation ->
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
        continuation.resume(granted)
    }
}

private const val TAG = "ScannerDialog"
