package n7.kcalai.feature.diary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.mascot.Mascot
import n7.kcalai.ui.mascot.MascotFigure
import n7.kcalai.ui.mascot.MascotPose
import n7.kcalai.ui.mascot.portalColor

/**
 * Места, куда персонаж может встать: плитка, чипс, реплика, сцена.
 *
 * Место само сообщает, где оно на экране, — персонажи рисуются отдельным слоем
 * поверх всего и о раскладке ленты не знают. Место, ушедшее с экрана, из реестра
 * пропадает: так [mascotPlaces] и узнаёт, что реплика за краем.
 */
class MascotAnchors {
    internal val rects = mutableStateMapOf<String, Rect>()
}

val LocalMascotAnchors = staticCompositionLocalOf<MascotAnchors?> { null }

fun homeAnchor(mascot: Mascot) = "home:$mascot"
fun stageAnchor(mascot: Mascot) = "stage:$mascot"
const val CHIP_ANCHOR = "chip"

/** Это место для персонажа. Размер места — размер персонажа на нём. */
fun Modifier.mascotAnchor(key: String): Modifier = composed {
    val anchors = LocalMascotAnchors.current ?: return@composed this
    DisposableEffect(anchors, key) { onDispose { anchors.rects.remove(key) } }
    onGloballyPositioned { anchors.rects[key] = it.boundsInRoot() }
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
        MascotPlace.Chip -> MascotPose.HUG
        is MascotPlace.Reply -> MascotPose.LEAN
        MascotPlace.Stage -> MascotPose.WAVE
    }

/**
 * Слой с тремя персонажами поверх экрана.
 *
 * Касания пропускает: сам слой не кликабелен, тап ловит только тело персонажа.
 */
@Composable
fun MascotStage(feed: List<FeedItem>, chipOwner: Mascot?, anchors: MascotAnchors, modifier: Modifier = Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val places = mascotPlaces(feed, anchors.rects.keys, chipOwner)

    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Mascot.entries.forEach { mascot ->
            StagedMascot(mascot, places.getValue(mascot), anchors, origin)
        }
    }
}

/**
 * Один персонаж и его портал.
 *
 * Не летит через экран, а проваливается в портал там, где стоит, и выныривает
 * из такого же на новом месте. Перелёт через полэкрана тянул за собой взгляд
 * и спорил с прокруткой; портал — это два коротких движения в двух точках.
 */
@Composable
private fun StagedMascot(mascot: Mascot, target: MascotPlace, anchors: MascotAnchors, origin: Offset) {
    var shown by remember { mutableStateOf(target) }
    val sink = remember { Animatable(0f) }
    val portal = remember { Animatable(0f) }

    LaunchedEffect(target) {
        if (target == shown && sink.value == 0f) return@LaunchedEffect
        if (target != shown) {
            portal.animateTo(1f, tween(PORTAL_MS))
            sink.animateTo(1f, tween(SINK_MS, easing = FastOutLinearInEasing))
            shown = target
            portal.snapTo(0f)
            portal.animateTo(1f, tween(PORTAL_MS))
        }
        sink.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        portal.animateTo(0f, tween(PORTAL_MS))
    }

    // Место пропало — свёрнутая шапка без плиток: персонажа просто не видно.
    val rect = anchors.rects[shown.anchorKey(mascot)] ?: return
    val density = LocalDensity.current
    val ring = mascot.portalColor
    val hole = KcalTheme.colors.text

    Box(
        Modifier
            .offset { IntOffset((rect.left - origin.x).roundToInt(), (rect.top - origin.y).roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
    ) {
        Canvas(Modifier.matchParentSize()) {
            val open = portal.value
            if (open <= 0f) return@Canvas
            val w = size.width * 1.3f * open
            val h = 12.dp.toPx()
            val topLeft = Offset(center.x - w / 2, size.height - h / 2)
            drawOval(hole, topLeft, Size(w, h))
            drawOval(ring, topLeft, Size(w, h), style = Stroke(width = 3.dp.toPx()))
        }
        MascotFigure(
            mascot = mascot,
            pose = shown.pose,
            modifier = Modifier
                .matchParentSize()
                // Пока проваливается, низ срезан по линии портала. В покое не режем:
                // ручки на чипсе свисают ниже рамки.
                .drawWithContent {
                    if (sink.value > 0f) {
                        clipRect(left = -size.width, top = -size.height, right = size.width * 2, bottom = size.height) {
                            this@drawWithContent.drawContent()
                        }
                    } else {
                        drawContent()
                    }
                }
                .graphicsLayer { translationY = sink.value * size.height * 1.15f },
        )
    }
}

private const val PORTAL_MS = 130
private const val SINK_MS = 220
