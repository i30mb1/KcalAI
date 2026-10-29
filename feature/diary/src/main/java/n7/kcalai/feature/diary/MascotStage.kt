package n7.kcalai.feature.diary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import n7.kcalai.ui.mascot.Mascot
import n7.kcalai.ui.mascot.MascotFigure
import n7.kcalai.ui.mascot.MascotPose

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
 * Один персонаж.
 *
 * Не летит через экран, а исчезает там, где стоял, и появляется на новом месте.
 * Перелёт через полэкрана тянул за собой взгляд и спорил с прокруткой.
 */
@Composable
private fun StagedMascot(mascot: Mascot, target: MascotPlace, anchors: MascotAnchors, origin: Offset) {
    var shown by remember { mutableStateOf(target) }
    // 1 — на месте, 0 — исчез.
    val presence = remember { Animatable(1f) }

    LaunchedEffect(target) {
        if (target != shown) {
            presence.animateTo(0f, tween(VANISH_MS, easing = FastOutLinearInEasing))
            shown = target
        }
        presence.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
    }

    // Место пропало — свёрнутая шапка без плиток: персонажа просто не видно.
    val rect = anchors.rects[shown.anchorKey(mascot)] ?: return
    val density = LocalDensity.current

    MascotFigure(
        mascot = mascot,
        pose = shown.pose,
        modifier = Modifier
            .offset { IntOffset((rect.left - origin.x).roundToInt(), (rect.top - origin.y).roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .graphicsLayer {
                val p = presence.value
                scaleX = p
                scaleY = p
                alpha = p.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
    )
}

private const val VANISH_MS = 160
