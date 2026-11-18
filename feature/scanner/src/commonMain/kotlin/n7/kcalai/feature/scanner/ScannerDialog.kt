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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductDraft
import n7.kcalai.ui.KcalTheme

/**
 * Окно съёмки — от штрих-кода до карточки нового продукта. Одно на Android и iOS:
 * своя у платформ только камера, см. [ScanCamera].
 *
 * Шаги сменяют друг друга в одном окне, и камера между ними не гаснет:
 *
 * - код: камера на весь экран, узкая рамка под штрих-код, внизу «Нет штрих-кода» —
 *   для развесного и домашнего, сразу к карточке;
 * - поиск: та же картинка, рамка зеленеет, внизу «Ищу товар…»;
 * - новый продукт: камера плавно приближается к таблице и рамка вытягивается
 *   под неё, сверху выходят трое — каждый просит своё, — снизу выезжает карточка.
 *
 * Отказ в доступе к камере — не тупик, а вторая дверь: цифры вписываются с клавиатуры.
 *
 * Шаг ведёт экранная модель — см. [ScanStep]. Окно показывает шаг и сообщает,
 * что произошло: код прочитан, карточка заполнена и подтверждена, окно закрыли.
 *
 * @param onScanned код прочитан камерой или набран руками — уже нормализованный
 *        до EAN-13. Отдаётся один раз за шаг кода.
 * @param onNoBarcode «Нет штрих-кода» на шаге кода: развесное, домашнее — дальше
 *        карточка без кода. Окно в дневнике одно, и других дверей к этикетке нет.
 * @param onSave карточка заполнена и человек подтвердил её. Окно само ничего
 *        не сохраняет: куда класть продукт, решает дневник.
 * @param findByName название с пачки → похожий продукт справочника, `null` — такого нет.
 *        Карточка его только предлагает, числа в поля не подставляет.
 * @param onPickMatch человек взял похожий из справочника вместо того, чтобы
 *        заводить продукт. Окно после этого закрывают снаружи, как после [onSave].
 * @param frameStats над камерой — счётчик кадров и время разбора. Для
 *        разработчика: человеку из магазина приложений он ничего не говорит.
 */
@Composable
fun ScannerDialog(
    step: ScanStep,
    onScanned: (String) -> Unit,
    onNoBarcode: () -> Unit,
    onSave: (gtin: String?, name: String, nutriments: Nutriments) -> Unit,
    findByName: suspend (String) -> FoodCandidate?,
    onPickMatch: (FoodCandidate) -> Unit,
    onDismiss: () -> Unit,
    frameStats: Boolean = false,
) {
    val label = step is ScanStep.NewProduct
    val searching = step is ScanStep.Searching
    val card = (step as? ScanStep.NewProduct)?.let { rememberScanState(it.gtin, it.name) }
    var typingCode by rememberSaveable { mutableStateOf(false) }

    val scanned by rememberUpdatedState(onScanned)
    val router = remember { BarcodeRouter { code -> scanned(code) } }

    // Камера собирается один раз за окно; шаг и карточка меняются у неё на глазах,
    // поэтому она читает их свежими, а не те, что были при сборке.
    val camera = rememberScanCamera(
        reading = { label },
        onBarcode = { code -> router.route(step, card, code) },
        onFrame = { frame -> card?.onFrame(frame) },
        outcome = { card?.let { it.outcome(it.saved) } },
    )

    if (card != null) {
        CardClock(card)
        ScanHaptics(card)
        LabelSecondOpinion(card, camera.secondOpinion)
        LabelNameMatch(card, findByName)
        LaunchedEffect(card, camera.answered, camera.granted) {
            if (camera.answered && !camera.granted) card.withoutCamera()
        }
    }
    CaughtHaptic(searching)

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
            typingCode && camera.granted -> typingCode = false
            else -> requestClose()
        }
    }

    // 0 — кадр на весь экран, как у штрих-кода; 1 — по ширине от верха, как у этикетки.
    val fit = remember { Animatable(if (label) 1f else 0f) }
    LaunchedEffect(label) {
        fit.animateTo(if (label) 1f else 0f, tween(FIT_MS, easing = FastOutSlowInEasing))
    }
    var labelZoom by rememberSaveable { mutableFloatStateOf(LABEL_ZOOM) }

    ScanDialog(onDismiss = back, onCamera = camera.granted) {
        Surface(Modifier.fillMaxSize(), color = if (camera.granted) CAMERA_BACKDROP else KcalTheme.colors.bg) {
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

                if (camera.granted) {
                    camera.Preview(
                        modifier = Modifier.pointerInput(Unit) {
                            // Тап по камере убирает клавиатуру: человек хочет ещё раз камерой.
                            detectTapGestures {
                                card?.stopTyping()
                                typingCode = false
                            }
                        },
                        // Приближение растёт вместе с переходом к этикетке, а не скачком:
                        // скачок зума посреди движения рамки читался бы как сбой.
                        zoom = 1f + (labelZoom - 1f) * t,
                        onPinch = if (label) { factor -> labelZoom = pinchedZoom(labelZoom, factor) } else null,
                        fit = t,
                    )
                    Viewfinder(top = window.top, height = window.height, caught = searching)
                }

                ScanTopBar(
                    title = if (label) "новый продукт" else "штрих-код",
                    meta = card?.takeIf { frameStats && it.seen > 0 }
                        ?.let { "кадр ${it.seen} · ${it.frame.elapsedMs} мс" },
                    onDismiss = requestClose,
                    modifier = Modifier.align(Alignment.TopCenter),
                    onCamera = camera.granted,
                    action = when {
                        label && camera.granted -> {
                            { ZoomPill(labelZoom) { labelZoom = nextZoomStop(labelZoom) } }
                        }
                        // Ручной ввод рядом с крестиком, а не вместо камеры: смазанный код
                        // на мятой пачке не читается никогда, и цифры под ним — рабочий путь.
                        step == ScanStep.Barcode && camera.granted && !typingCode -> {
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

                val bottom = scanBottom(card, typingCode, camera.answered, camera.granted, searching)
                // Кадр 3:4 стоит по ширине от верха — ниже него только панель,
                // и она обязана закрыть всё, что ниже кадра.
                val cardMax = (maxHeight - (shelfTop + SHELF_HEIGHT + 8.dp)).coerceAtLeast(0.dp)
                val cardMin = if (camera.granted) (maxHeight - maxWidth * 4f / 3f).coerceIn(0.dp, cardMax) else 0.dp

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
                            HintPill(text = scanHint(shown.searching, camera.answered), busy = shown.searching)
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
                            title = if (camera.granted) {
                                "Наберите цифры под штрих-кодом"
                            } else {
                                "Доступ к камере не разрешён — наберите цифры под штрих-кодом"
                            },
                            searching = shown.searching,
                            onSubmit = router::submit,
                            onNoCode = onNoBarcode,
                        )

                        is Bottom.Card -> ProductCard(
                            state = shown.state,
                            hint = panelHint(
                                camera = camera.granted,
                                answered = camera.answered,
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

/**
 * Нейросеть читает этикетку, когда камера восемь секунд не прибавляет чисел.
 *
 * Один раз на карточку и только по последнему резкому кадру. Ответ встаёт
 * в пустые поля, если сходится по Этуотеру, — решает карточка, а не здесь.
 * Без читателя на телефоне ничего не происходит: карточка ждёт камеру и человека.
 */
@Composable
private fun LabelSecondOpinion(card: ScanState, read: (suspend () -> ProductDraft?)?) {
    if (read == null) return
    val due = card.visionDue
    LaunchedEffect(card, due) {
        if (!due) return@LaunchedEffect
        card.onVision(read())
    }
}

/**
 * Справочник ищется по названию с пачки — с паузой. Кандидаты в названия
 * сменяются от кадра к кадру, человек набирает название по букве, а поиск
 * по смыслу считает отпечаток запроса моделью: искать каждую промежуточную
 * строку незачем, нужна та, на которой остановились.
 */
@Composable
private fun LabelNameMatch(card: ScanState, findByName: suspend (String) -> FoodCandidate?) {
    val query = card.nameQuery
    val find by rememberUpdatedState(findByName)
    LaunchedEffect(card, query) {
        if (query == null) return@LaunchedEffect
        delay(MATCH_DELAY_MS)
        card.onMatch(query, find(query))
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
