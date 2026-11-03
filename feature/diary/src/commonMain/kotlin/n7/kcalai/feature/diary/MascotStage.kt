package n7.kcalai.feature.diary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import n7.kcalai.mascot.CueKind
import n7.kcalai.mascot.MascotEvent
import n7.kcalai.model.Mascot
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.MacroColors
import n7.kcalai.ui.colors
import n7.kcalai.ui.mascot.IDLE_SLEEP_MS
import n7.kcalai.ui.mascot.IDLE_YAWN_MS
import n7.kcalai.ui.mascot.MascotAct
import n7.kcalai.ui.mascot.MascotFace
import n7.kcalai.ui.mascot.MascotFigure
import n7.kcalai.ui.mascot.MascotPose
import n7.kcalai.ui.mascot.MascotPuppet
import n7.kcalai.ui.mascot.PokeMeter
import n7.kcalai.ui.mascot.SpeechRequest
import n7.kcalai.ui.mascot.gaze
import n7.kcalai.ui.mascot.macro
import n7.kcalai.ui.mascot.rememberMascotPuppet
import n7.kcalai.ui.mascot.speechPlacements
import n7.kcalai.ui.popIn

/**
 * Места, куда персонаж может встать: плитка, чипс, реплика, сцена.
 *
 * Место само сообщает, где оно на экране, — персонажи рисуются отдельным слоем
 * поверх всего и о раскладке ленты не знают. Место, ушедшее с экрана, из реестра
 * пропадает: так [mascotPlaces] и узнаёт, что реплика за краем.
 */
class MascotAnchors {
    internal val rects = mutableStateMapOf<String, Rect>()

    /** Что облачку закрывать нельзя: реплики ленты, записи, строка ввода. */
    internal val obstacles = mutableStateMapOf<String, Rect>()

    /** Нижний край шапки: лента идёт под ней, и что ушло под шапку, не видно. См. [shownAnchors]. */
    internal var coverBottom by mutableFloatStateOf(0f)

    /** Верхний край строки ввода: под неё лента уходит так же, как под шапку. */
    internal var floorTop by mutableFloatStateOf(Float.MAX_VALUE)
}

val LocalMascotAnchors = staticCompositionLocalOf<MascotAnchors?> { null }

internal const val HOME_PREFIX = "home:"
fun homeAnchor(mascot: Mascot) = "$HOME_PREFIX$mascot"
fun stageAnchor(mascot: Mascot) = "stage:$mascot"
const val CHIP_ANCHOR = "chip"

/**
 * Это место для персонажа. Размер места — размер персонажа на нём.
 *
 * Прямоугольник берётся целиком, а не `boundsInRoot()`: тот обрезан по родителю,
 * а место над чипсом нарочно выходит за ряд подсказок, который режет всё лишнее, —
 * персонажу доставался сплющенный огрызок. Рисуется он всё равно поверх экрана.
 */
fun Modifier.mascotAnchor(key: String): Modifier = composed {
    val anchors = LocalMascotAnchors.current ?: return@composed this
    DisposableEffect(anchors, key) { onDispose { anchors.rects.remove(key) } }
    onGloballyPositioned { anchors.rects[key] = Rect(it.positionInRoot(), it.size.toSize()) }
}

/**
 * Это облачку не закрывать: реплику ленты, запись, строку ввода.
 *
 * Персонаж стоит в углу своей реплики, и облачко над ним ложилось на её текст —
 * текст на текст, светлое на светлое. Берётся видимая часть: что ушло под шапку
 * или за край ленты, закрыть уже нельзя. Ушедшее с экрана из реестра пропадает.
 */
fun Modifier.speechObstacle(key: String): Modifier = composed {
    val anchors = LocalMascotAnchors.current ?: return@composed this
    DisposableEffect(anchors, key) { onDispose { anchors.obstacles.remove(key) } }
    onGloballyPositioned { anchors.obstacles[key] = it.boundsInRoot() }
}

/**
 * Где палец на экране прямо сейчас и сколько раз касались.
 *
 * Пишет наблюдатель касаний в корне экрана, читают персонажи — внутри своих
 * эффектов, поэтому движение пальца экран не перекомпоновывает.
 */
@Stable
class StagePointer {
    var position: Offset? by mutableStateOf(null)
        internal set

    var touches: Int by mutableIntStateOf(0)
        internal set
}

/**
 * Наблюдатель касаний: видит каждое и ничего не забирает.
 *
 * Слушает на первом проходе, раньше ленты и кнопок, — поэтому взгляд следит
 * за пальцем, даже когда тот листает ленту или жмёт чипс.
 */
fun Modifier.observeTouches(pointer: StagePointer): Modifier = pointerInput(pointer) {
    awaitPointerEventScope {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: continue
            if (change.pressed && !change.previousPressed) pointer.touches++
            pointer.position = if (change.pressed) change.position else null
        }
    }
}

private fun MascotPlace.anchorKey(mascot: Mascot): String = when (this) {
    MascotPlace.Home -> homeAnchor(mascot)
    MascotPlace.Chip -> CHIP_ANCHOR
    is MascotPlace.Reply -> key
    MascotPlace.Stage -> stageAnchor(mascot)
}

private val MascotPlace.pose: MascotPose
    get() = when (this) {
        MascotPlace.Home -> MascotPose.HOME
        MascotPlace.Chip -> MascotPose.PEEK
        is MascotPlace.Reply -> MascotPose.LEAN
        MascotPlace.Stage -> MascotPose.WAVE
    }

/** Один персонаж на сцене: кукла и то, что с ним делает палец. */
@Stable
private class Actor(val mascot: Mascot, val puppet: MascotPuppet) {
    /** Насколько его утащили от места. */
    val drag = Animatable(Offset.Zero, Offset.VectorConverter)

    /** Наклон, пока болтается на пальце. */
    val tilt = Animatable(0f)
    val pokes = PokeMeter()
    var held by mutableStateOf(false)

    /** Где он сейчас на экране, без учёта пальца. */
    var rect: Rect? by mutableStateOf(null)

    /** Сколько пальцем проведено по нему подряд — для «погладили». */
    var stroke = 0f
    var holding: Job? = null

    /** Подействовать: спящий сначала просыпается — прыжки во сне смотрятся странно. */
    suspend fun perform(act: MascotAct) {
        if (puppet.asleep) puppet.wake(startled = false)
        puppet.play(act)
    }
}

/**
 * Слой с тремя персонажами поверх экрана — и всё, что делает их живыми.
 *
 * Сам слой касаний не ловит: их ловит только тело персонажа. Остальное — ответы
 * на то, что происходит: палец на экране — взгляд, набор текста — взгляд вниз,
 * тишина — зевок и сон, ночь — сон сразу, сигналы экранной модели — прыжки
 * и праздники, реплики — облачка. Все движения конечны: без человека сцена
 * засыпает и замирает.
 */
@Composable
fun MascotStage(
    feed: List<FeedItem>,
    chipOwner: Mascot?,
    anchors: MascotAnchors,
    pointer: StagePointer,
    input: String,
    speech: List<SpeechUi>,
    cue: CueUi?,
    night: Boolean,
    onEvent: (MascotEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val places = mascotPlaces(feed, shownAnchors(anchors.rects, anchors.coverBottom, anchors.floorTop), chipOwner)
    val puppets = Mascot.entries.map { rememberMascotPuppet(it) }
    val actors = remember(puppets) { puppets.associate { it.mascot to Actor(it.mascot, it) } }
    val event by rememberUpdatedState(onEvent)
    val reach = with(LocalDensity.current) { GAZE_REACH.toPx() }

    // Набор текста — тоже внимание: клавиатура живёт в своём окне, и касаний её
    // наблюдатель не видит.
    val typing = remember { mutableIntStateOf(0) }
    LaunchedEffect(input) {
        if (input.isEmpty()) return@LaunchedEffect
        typing.intValue++
        actors.values.forEach { launch { it.puppet.lookAt(Offset(0f, 1f)) } }
        delay(TYPING_GLANCE_MS)
        actors.values.forEach { launch { it.puppet.lookAt(Offset.Zero) } }
    }

    Sleep(actors, pointer, typing, night, onWoken = { event(it) })
    Cues(actors, cue)

    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        actors.values.forEach { actor ->
            Gaze(actor, pointer, reach)
            Blinking(actor, pointer, typing)
            StagedMascot(actor, places.getValue(actor.mascot), anchors, origin, night) { event(it) }
        }
        SpeechClouds(speech, actors, anchors.obstacles, origin)
    }
}

/**
 * Тишина и ночь.
 *
 * Днём: касание будит задремавших, сорок секунд тишины — кто-то зевает, минута —
 * спят все и замирают. Ночью персонажей застают спящими; разбудить можно только
 * тапом по самому персонажу, и через двадцать секунд он снова засыпает.
 */
@Composable
private fun Sleep(
    actors: Map<Mascot, Actor>,
    pointer: StagePointer,
    typing: MutableIntState,
    night: Boolean,
    onWoken: (MascotEvent) -> Unit,
) {
    LaunchedEffect(actors, night) {
        var first = true
        snapshotFlow { pointer.touches + typing.intValue }.collectLatest {
            val awake = actors.values.filterNot { it.held }
            if (night) {
                if (first) awake.forEach { launch { it.puppet.sleep() } }
                first = false
                delay(NIGHT_DOZE_MS)
                awake.filterNot { it.puppet.asleep }.forEach { launch { it.puppet.sleep() } }
                return@collectLatest
            }
            first = false
            val sleepy = actors.values.filter { it.puppet.asleep }
            if (sleepy.isNotEmpty()) {
                sleepy.forEach { launch { it.puppet.wake(startled = false) } }
                onWoken(MascotEvent.Woken(sleepy.random().mascot, night = false))
            }
            delay(IDLE_YAWN_MS)
            awake.randomOrNull()?.puppet?.play(MascotAct.YAWN)
            delay(IDLE_SLEEP_MS - IDLE_YAWN_MS)
            actors.values.filterNot { it.held }.forEach { launch { it.puppet.sleep() } }
        }
    }
}

/** Сигналы экранной модели — в движения. */
@Composable
private fun Cues(actors: Map<Mascot, Actor>, cue: CueUi?) {
    LaunchedEffect(cue?.id) {
        val signal = cue ?: return@LaunchedEffect
        val ordered = Mascot.entries.mapNotNull { actors[it] }
        when (signal.kind) {
            CueKind.PROUD, CueKind.OPENED -> signal.mascot?.let { actors[it] }?.perform(MascotAct.HOP)
            // По очереди, а не хором: хором — механика, по очереди — компания.
            CueKind.CELEBRATE -> ordered.forEachIndexed { index, actor ->
                launch {
                    delay(index * STAGGER_MS)
                    actor.perform(MascotAct.CELEBRATE)
                }
            }
            CueKind.GREET -> ordered.forEachIndexed { index, actor ->
                launch {
                    delay(index * STAGGER_MS)
                    actor.perform(MascotAct.HOP)
                }
            }
            CueKind.DIZZY -> ordered.forEach { launch { it.perform(MascotAct.DIZZY) } }
        }
    }
}

/** Взгляд за пальцем; отпустил — через полсекунды снова смотрит на человека. */
@Composable
private fun Gaze(actor: Actor, pointer: StagePointer, reach: Float) {
    LaunchedEffect(actor) {
        snapshotFlow { pointer.position?.let { finger -> actor.rect?.let { gaze(it.center, finger, reach) } } }
            .collectLatest { target ->
                if (target == null) {
                    delay(LOOK_BACK_MS)
                    actor.puppet.lookAt(Offset.Zero)
                } else {
                    actor.puppet.lookAt(target)
                }
            }
    }
}

/** Моргает, пока с ним что-то происходит, — двенадцать секунд после касания, потом замирает. */
@Composable
private fun Blinking(actor: Actor, pointer: StagePointer, typing: MutableIntState) {
    LaunchedEffect(actor) {
        snapshotFlow { pointer.touches + typing.intValue }.collectLatest {
            var elapsed = 0L
            while (elapsed < BLINK_WINDOW_MS) {
                val wait = Random.nextLong(BLINK_MIN_MS, BLINK_MAX_MS)
                delay(wait)
                elapsed += wait
                actor.puppet.blink()
            }
        }
    }
}

/**
 * Один персонаж на своём месте.
 *
 * Не летит через экран, а исчезает там, где стоял, и появляется на новом месте.
 * Перелёт через полэкрана тянул за собой взгляд и спорил с прокруткой. Пока его
 * держат пальцем, место не меняется: персонаж в руке не должен растворяться.
 */
@Composable
private fun StagedMascot(
    actor: Actor,
    target: MascotPlace,
    anchors: MascotAnchors,
    origin: Offset,
    night: Boolean,
    onEvent: (MascotEvent) -> Unit,
) {
    var shown by remember { mutableStateOf(target) }
    // 1 — на месте, 0 — исчез.
    val presence = remember { Animatable(1f) }

    LaunchedEffect(target, actor.held) {
        if (actor.held) return@LaunchedEffect
        if (target != shown) {
            presence.animateTo(0f, tween(VANISH_MS, easing = FastOutLinearInEasing))
            shown = target
        }
        presence.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
    }

    // Место пропало — свёрнутая шапка без плиток: персонажа просто не видно.
    val rect = anchors.rects[shown.anchorKey(actor.mascot)]
    SideEffect { actor.rect = rect }
    rect ?: return

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val strokeToPet = rect.width * PET_WIDTHS
    // На чипсе выглядывает из-за кромки: низ места — это край чипса, ниже не рисуем.
    val peek = shown == MascotPlace.Chip && !actor.held

    MascotFigure(
        puppet = actor.puppet,
        pose = if (actor.held) MascotPose.HELD else shown.pose,
        modifier = Modifier
            .offset {
                IntOffset(
                    (rect.left - origin.x + actor.drag.value.x).roundToInt(),
                    (rect.top - origin.y + actor.drag.value.y).roundToInt(),
                )
            }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .drawWithContent {
                if (peek) {
                    clipRect(left = -size.width, top = -size.height, right = size.width * 2, bottom = size.height) {
                        this@drawWithContent.drawContent()
                    }
                } else {
                    drawContent()
                }
            }
            .graphicsLayer {
                val p = presence.value
                val lift = if (actor.held) HELD_SCALE else 1f
                scaleX = p * lift
                scaleY = p * lift
                alpha = p.coerceIn(0f, 1f)
                rotationZ = actor.tilt.value
                transformOrigin = TransformOrigin(0.5f, if (actor.held) 0.1f else 1f)
                // Опущен за кромку так, что остаются макушка и глаза.
                if (peek) translationY = size.height * PEEK_DEPTH
            }
            .mascotGestures(
                onTap = { atMs ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch {
                        if (actor.puppet.asleep) {
                            actor.puppet.wake(startled = true)
                            onEvent(MascotEvent.Woken(actor.mascot, night))
                            return@launch
                        }
                        val poke = actor.pokes.poke(atMs)
                        if (poke.trick) {
                            onEvent(MascotEvent.Trick(actor.mascot))
                            actor.puppet.play(MascotAct.TRICK)
                        } else {
                            onEvent(MascotEvent.Poked(actor.mascot, poke.count))
                            actor.puppet.play(if (poke.count == SULK_AT) MascotAct.SULK else MascotAct.SHAKE)
                        }
                    }
                },
                onHold = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    actor.held = true
                    actor.holding = scope.launch { actor.puppet.hold(actor.mascot.heldFace) }
                    onEvent(MascotEvent.Held(actor.mascot))
                },
                onDrag = { delta ->
                    scope.launch {
                        actor.drag.snapTo(actor.drag.value + delta)
                        // Болтается на пальце: тянут вправо — отклоняется влево, и обратно.
                        val swing = (-delta.x * SWING_PER_PX).coerceIn(-MAX_SWING, MAX_SWING)
                        actor.tilt.snapTo(actor.tilt.value * 0.8f + swing * 0.2f)
                    }
                },
                onRelease = { velocity ->
                    actor.held = false
                    actor.holding?.cancel()
                    val thrown = hypot(velocity.x, velocity.y) > THROW_SPEED
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onEvent(MascotEvent.Dropped(actor.mascot, thrown))
                    scope.launch {
                        launch { actor.drag.animateTo(Offset.Zero, spring(dampingRatio = 0.55f, stiffness = 180f)) }
                        launch { actor.tilt.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 200f)) }
                        actor.puppet.play(if (thrown) MascotAct.DIZZY else MascotAct.NOD)
                    }
                },
                onStroke = { distance ->
                    actor.stroke += distance
                    if (actor.stroke >= strokeToPet) {
                        actor.stroke = 0f
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onEvent(MascotEvent.Petted(actor.mascot))
                        scope.launch { actor.perform(if (actor.mascot == Mascot.BUTTER) MascotAct.MELT else MascotAct.BLISS) }
                    }
                },
                onStrokeEnd = { actor.stroke = 0f },
            ),
    )
}

/** Лицо на пальце — по характеру: Яйцо боится разбиться, Масло тает от тепла рук, Зёрнышко в восторге. */
private val Mascot.heldFace: MascotFace
    get() = when (this) {
        Mascot.EGG -> MascotFace.WORRIED
        Mascot.BUTTER -> MascotFace.BLISS
        Mascot.GRAIN -> MascotFace.HAPPY
    }

/**
 * Жесты по телу персонажа: тап, долгое нажатие с перетаскиванием, поглаживание.
 *
 * Тап отыгрывается сразу, без ожидания второго: двойной тап узнаёт [PokeMeter]
 * по второму быстрому тычку. Сдвиг пальца раньше долгого нажатия — поглаживание,
 * долгое нажатие — персонажа взяли в руку.
 */
private fun Modifier.mascotGestures(
    onTap: (atMs: Long) -> Unit,
    onHold: () -> Unit,
    onDrag: (Offset) -> Unit,
    onRelease: (Velocity) -> Unit,
    onStroke: (Float) -> Unit,
    onStrokeEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        var stroked = false
        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (!change.pressed) return@withTimeoutOrNull true
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    stroked = true
                    return@withTimeoutOrNull false
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        when {
            tapped == true -> onTap(down.uptimeMillis)
            stroked -> {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    onStroke(change.positionChange().getDistance())
                    change.consume()
                }
                onStrokeEnd()
            }
            tapped == null -> {
                onHold()
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) break
                    onDrag(change.positionChange())
                    change.consume()
                }
                onRelease(tracker.calculateVelocity())
            }
        }
    }
}

/**
 * Облачка с репликами над персонажами — одной раскладкой на всех.
 *
 * Облачко встаёт над персонажем или под ним — там, где не закроет реплику ленты,
 * запись и строку ввода, а двое, заговорившие разом, не ложатся друг на друга.
 * Цвет — его персонажа, как плитка его макроса в шапке: белое облачко поверх
 * белой реплики сливалось с ней в одно пятно, а по цвету ещё и видно, кто говорит.
 *
 * Хвостик смотрит на персонажа; облачко едет за ним, даже если его тащат.
 * Касаний не ловит: сквозь облачко листается лента.
 */
@Composable
private fun SpeechClouds(
    lines: List<SpeechUi>,
    actors: Map<Mascot, Actor>,
    obstacles: Map<String, Rect>,
    origin: Offset,
) {
    val density = LocalDensity.current
    val margin = with(density) { SPEECH_MARGIN.roundToPx() }
    val gap = with(density) { SPEECH_GAP.roundToPx() }
    val inset = with(density) { SPEECH_TAIL_INSET.roundToPx() }
    val overlap = with(density) { 1.dp.roundToPx() }

    Layout(
        content = {
            lines.forEach { line ->
                key(line.id) {
                    val palette = line.mascot.macro.colors()
                    SpeechBody(line.text, palette)
                    SpeechTail(up = true, palette)
                    SpeechTail(up = false, palette)
                }
            }
        },
    ) { measurables, constraints ->
        val free = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        // На каждую реплику — три куска: облачко и два хвостика, вверх и вниз.
        val parts = measurables.map { it.measure(free) }.chunked(3)
        layout(constraints.maxWidth, constraints.maxHeight) {
            val shown = lines.indices.mapNotNull { index ->
                val actor = actors[lines[index].mascot] ?: return@mapNotNull null
                val rect = actor.rect ?: return@mapNotNull null
                index to rect.translate(actor.drag.value - origin)
            }
            val placed = speechPlacements(
                requests = shown.map { (index, anchor) ->
                    SpeechRequest(anchor, IntSize(parts[index][0].width, parts[index][0].height))
                },
                bounds = IntSize(constraints.maxWidth, constraints.maxHeight),
                marginPx = margin,
                taken = obstacles.values.map { it.translate(-origin) },
                gapPx = gap,
                tailInsetPx = inset,
            )
            shown.forEachIndexed { order, (index, _) ->
                val (body, upTail, downTail) = parts[index]
                val at = placed[order]
                body.place(at.offset)
                if (at.tailUp) {
                    upTail.place(at.offset.x + at.tailX - upTail.width / 2, at.offset.y - upTail.height + overlap)
                } else {
                    downTail.place(at.offset.x + at.tailX - downTail.width / 2, at.offset.y + body.height - overlap)
                }
            }
        }
    }
}

/** Само облачко: над персонажем оно или под ним, решает раскладка — растёт оно из середины. */
@Composable
private fun SpeechBody(text: String, palette: MacroColors) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .popIn(lift = 6.dp)
            .widthIn(max = SPEECH_MAX_WIDTH)
            .shadow(3.dp, shape)
            .clip(shape)
            .background(palette.tile)
            .border(1.dp, palette.fill.copy(alpha = SPEECH_EDGE_ALPHA), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, style = KcalTheme.type.body, color = palette.text)
    }
}

/** Хвостик облачка: треугольник той же бумаги, обведённый той же линией. */
@Composable
private fun SpeechTail(up: Boolean, palette: MacroColors) {
    Canvas(Modifier.size(width = 14.dp, height = 8.dp)) {
        val path = Path().apply {
            if (up) {
                moveTo(0f, size.height)
                lineTo(size.width / 2, 0f)
                lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width / 2, size.height)
                lineTo(size.width, 0f)
            }
        }
        drawPath(path, palette.tile)
        drawPath(path, palette.fill.copy(alpha = SPEECH_EDGE_ALPHA), style = Stroke(width = 1.dp.toPx()))
    }
}

private const val VANISH_MS = 160

/** Опущен так, что над кромкой глаза, а рот уже за ней: половинка рта смотрится обрывком. */
private const val PEEK_DEPTH = 0.27f

/** Шестой тык подряд — обида: отворачивается. */
private const val SULK_AT = 6

private const val HELD_SCALE = 1.12f
private const val SWING_PER_PX = 0.9f
private const val MAX_SWING = 24f

/** Быстрее этого отпущенный — брошен: голова кружится. Пикселей в секунду. */
private const val THROW_SPEED = 1_800f

/** Сколько ширин персонажа провести пальцем, чтобы это считалось поглаживанием. */
private const val PET_WIDTHS = 1.5f

private const val TYPING_GLANCE_MS = 1_200L
private const val LOOK_BACK_MS = 700L
private const val BLINK_WINDOW_MS = 12_000L
private const val BLINK_MIN_MS = 1_800L
private const val BLINK_MAX_MS = 4_500L
private const val NIGHT_DOZE_MS = 20_000L
private const val STAGGER_MS = 140L

private val GAZE_REACH = 160.dp
private val SPEECH_MAX_WIDTH = 220.dp
private val SPEECH_MARGIN = 12.dp
private val SPEECH_GAP = 6.dp
private val SPEECH_TAIL_INSET = 16.dp

/** Кромка облачка — цветом персонажа, но вполсилы: обводка, а не рамка. */
private const val SPEECH_EDGE_ALPHA = 0.4f
