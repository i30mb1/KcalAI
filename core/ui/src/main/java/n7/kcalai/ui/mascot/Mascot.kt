package n7.kcalai.ui.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.launch
import n7.kcalai.ui.Macro
import n7.kcalai.ui.mascot.butter.ButterArt
import n7.kcalai.ui.mascot.egg.EggArt
import n7.kcalai.ui.mascot.grain.GrainArt

/**
 * Трое, с кем человек переписывается: каждый — свой макрос и узнаваемый продукт.
 *
 * Персонажи статичны: не растут и не меняются от съеденного. Цифры живут в тексте
 * реплики, а персонаж — это голос, и голос должен узнаваться с первого взгляда,
 * в 24 dp и без цвета.
 *
 * Каждый лежит в своей папке ([EggArt], [ButterArt], [GrainArt]): цвета, контуры
 * и характер движения меняются там, не задевая остальных.
 */
enum class Mascot(internal val art: MascotArt) {
    /** Белок. Яйцо в скорлупе-шапке. */
    EGG(EggArt),

    /** Жиры. Брусок масла с завитком. */
    BUTTER(ButterArt),

    /** Углеводы. Зёрнышко с хохолком. */
    GRAIN(GrainArt),
}

/** Кто отвечает за макрос: в плитке шапки сидит тот же, кто говорит о нём в ленте. */
val Macro.mascot: Mascot
    get() = when (this) {
        Macro.PROTEIN -> Mascot.EGG
        Macro.FAT -> Mascot.BUTTER
        Macro.CARB -> Mascot.GRAIN
    }

/**
 * Рисунок одного персонажа на холсте [VIEW_W]×[VIEW_H] — тех же координатах,
 * что у макетов, чтобы контуры переносились из них один в один.
 */
internal interface MascotArt {

    /** Полупериод дыхания. У троих разный: синхронно дышащая лента выглядит механической. */
    val breathMs: Int

    fun DrawScope.draw()
}

internal const val VIEW_W = 120f
internal const val VIEW_H = 100f

/**
 * Персонаж у входящего пузырька.
 *
 * Желейный: при появлении трясётся, от тапа — ещё раз, в покое еле заметно дышит.
 * Размер задаёт [modifier]; пропорцию держать 6:5, как у холста.
 *
 * [lively] = `false` — неподвижный, для шапки: там рядом цифры, и шевелящаяся
 * картинка отвлекала бы от них. Трясётся тот, кто говорит, — то есть в ленте.
 */
@Composable
fun MascotAvatar(mascot: Mascot, modifier: Modifier = Modifier, lively: Boolean = true) {
    if (!lively) {
        Canvas(modifier) {
            scale(size.width / VIEW_W, size.height / VIEW_H, pivot = Offset.Zero) {
                with(mascot.art) { draw() }
            }
        }
        return
    }

    // Тряска — затухающая синусоида по прогрессу 0→1. В покое прогресс 1: поворот ноль.
    val shake = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    suspend fun wobble() {
        shake.snapTo(0f)
        shake.animateTo(1f, tween(SHAKE_MS, easing = LinearEasing))
    }
    LaunchedEffect(Unit) { wobble() }

    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(mascot.art.breathMs, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "breath",
    )

    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { scope.launch { wobble() } } }
            .graphicsLayer {
                val p = shake.value
                val angle = sin(p * PI * SHAKE_SWINGS).toFloat() * SHAKE_DEGREES * (1f - p)
                rotationZ = angle
                // Желе приседает, пока качается, и чуть дышит в покое.
                val squash = abs(angle) / 120f + breath * 0.035f
                scaleY = 1f - squash
                scaleX = 1f + squash * 0.8f
                transformOrigin = TransformOrigin(0.5f, 0.95f)
            }
    ) {
        scale(size.width / VIEW_W, size.height / VIEW_H, pivot = Offset.Zero) {
            with(mascot.art) { draw() }
        }
    }
}

private const val SHAKE_MS = 620
private const val SHAKE_SWINGS = 5
private const val SHAKE_DEGREES = 11f
