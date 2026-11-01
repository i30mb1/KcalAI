package n7.kcalai.ui.mascot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.launch
import n7.kcalai.model.Mascot
import n7.kcalai.ui.Macro
import n7.kcalai.ui.mascot.butter.ButterArt
import n7.kcalai.ui.mascot.egg.EggArt
import n7.kcalai.ui.mascot.grain.GrainArt

/**
 * Рисунок персонажа.
 *
 * Персонажи не растут и не меняются от съеденного. Цифры живут в тексте
 * реплики, а персонаж — это голос, и голос должен узнаваться с первого взгляда,
 * в 24 dp и без цвета.
 *
 * Каждый лежит в своей папке ([EggArt], [ButterArt], [GrainArt]): цвета, контуры
 * и характер движения меняются там, не задевая остальных.
 */
internal val Mascot.art: MascotArt
    get() = when (this) {
        Mascot.EGG -> EggArt
        Mascot.BUTTER -> ButterArt
        Mascot.GRAIN -> GrainArt
    }

/** Кто отвечает за макрос: в плитке шапки сидит тот же, кто говорит о нём в ленте. */
val Macro.mascot: Mascot
    get() = when (this) {
        Macro.PROTEIN -> Mascot.EGG
        Macro.FAT -> Mascot.BUTTER
        Macro.CARB -> Mascot.GRAIN
    }

/** Макрос персонажа: цвет его плитки, его аватарки в шторке. */
val Mascot.macro: Macro
    get() = when (this) {
        Mascot.EGG -> Macro.PROTEIN
        Mascot.BUTTER -> Macro.FAT
        Mascot.GRAIN -> Macro.CARB
    }

/** Что делают ручки. Тело одно на все позы — меняются только они. */
enum class MascotPose {
    /** Дома в плитке: ручки поджаты, рядом цифры. */
    HOME,

    /** На чипсе: выглядывает из-за кромки, ручки не нужны — видны макушка и глаза. */
    PEEK,

    /** У реплики: облокотился на угол пузырька. */
    LEAN,

    /** В пустой ленте: машет. */
    WAVE,

    /** Его подняли пальцем: обе ручки вверх. */
    HELD,
}

/**
 * Рисунок одного персонажа на холсте [VIEW_W]×[VIEW_H] — тех же координатах,
 * что у макетов, чтобы контуры переносились из них один в один.
 *
 * Тело — бумага, лицо — ручкой поверх и отдельно: выражения у троих общие,
 * а где у кого глаза и рот, говорит [face].
 */
internal interface MascotArt {

    /** Полупериод дыхания. У троих разный: синхронно дышащая лента выглядит механической. */
    val breathMs: Int

    /** Ручки — того же цвета, что тело, с тёмной каймой: иначе на теле их не видно. */
    val skin: Color
    val armOutline: Color

    val face: FaceGeometry

    fun DrawScope.drawBody()
}

internal const val VIEW_W = 120f
internal const val VIEW_H = 100f

/**
 * Персонаж в позе [pose], сам по себе: дышит, в [MascotPose.WAVE] машет, от тапа трясётся.
 *
 * Для мест, где он просто живёт, а не играет со сценой. Дышит и машет несколько раз —
 * встав в новую позу или от тапа, — а потом замирает. Бесконечная анимация держала
 * экран в перерисовке на частоте дисплея всё время, пока открыт дневник: девяносто
 * кадров в секунду над неподвижной лентой — батарея, а экран не мог сбросить частоту.
 * Заодно она не давала инструментам доступности и тестам интерфейса дождаться покоя.
 */
@Composable
fun MascotFigure(mascot: Mascot, pose: MascotPose, modifier: Modifier = Modifier) {
    val puppet = rememberMascotPuppet(mascot)
    val scope = rememberCoroutineScope()
    MascotFigure(
        puppet = puppet,
        pose = pose,
        modifier = modifier.pointerInput(puppet) {
            detectTapGestures {
                scope.launch { puppet.play(MascotAct.SHAKE) }
                scope.launch { puppet.breathe(BREATHS) }
            }
        },
    )
}

/**
 * Персонаж, которым управляет [puppet]: лицо, взгляд, действия задаёт сцена.
 *
 * Ручки выходят за холст — у [MascotPose.LEAN] тянутся к пузырьку, — поэтому
 * обрезать рисунок по рамке нельзя. Размер задаёт [modifier]; пропорция 6:5.
 * Значения куклы читаются при рисовании: анимация перерисовывает только персонажа.
 */
@Composable
fun MascotFigure(puppet: MascotPuppet, pose: MascotPose, modifier: Modifier = Modifier) {
    LaunchedEffect(puppet, pose) {
        launch { puppet.breathe(BREATHS) }
        if (pose == MascotPose.WAVE) launch { puppet.wave(WAVES) }
    }
    val art = puppet.mascot.art

    Canvas(
        modifier.graphicsLayer {
            // Тряска — затухающая синусоида по прогрессу 0→1, головокружение — медленнее и шире.
            val shake = puppet.shake.value
            val shakeAngle = sin(shake * PI * SHAKE_SWINGS).toFloat() * SHAKE_DEGREES * (1f - shake)
            val wobble = puppet.wobble.value
            val wobbleAngle = sin(wobble * PI * WOBBLE_SWINGS).toFloat() * WOBBLE_DEGREES * (1f - wobble)
            rotationZ = shakeAngle + wobbleAngle
            // Желе приседает, пока качается, и чуть дышит в покое.
            val squash = abs(shakeAngle) / 120f + puppet.breath.value * 0.035f + puppet.squash.value
            scaleY = 1f - squash
            scaleX = (1f + squash * 0.8f) * puppet.turn.value
            translationY = -puppet.hop.value * size.height * HOP_HEIGHT
            translationX = puppet.slide.value * size.width * SLIDE_WIDTH
            transformOrigin = TransformOrigin(0.5f, 0.95f)
        }
    ) {
        scale(size.width / VIEW_W, size.height / VIEW_H, pivot = Offset.Zero) {
            // Сальто — вокруг середины тела, а не вокруг ног: иначе это колесо по экрану.
            rotate(puppet.spin.value, pivot = SPIN_PIVOT) {
                arms(pose, art.skin, art.armOutline, waveDegrees = -WAVE_DEGREES * puppet.wave.value)
                with(art) { drawBody() }
                face(art.face, puppet.face, puppet.gaze.value, puppet.blinking.value)
            }
            puppet.emote?.let { emote(it, puppet.emoteProgress.value) }
        }
    }
}

/** Вздохов после новой позы или тапа: достаточно, чтобы заметить, что он живой. */
private const val BREATHS = 3

/** Взмахов на знакомстве. */
private const val WAVES = 4

private const val SHAKE_SWINGS = 5
private const val SHAKE_DEGREES = 11f
private const val WOBBLE_SWINGS = 4
private const val WOBBLE_DEGREES = 14f
private const val WAVE_DEGREES = 28f

/** Высота прыжка — доля роста. */
private const val HOP_HEIGHT = 0.45f

/** Насколько далеко скользит Масло — доля ширины. */
private const val SLIDE_WIDTH = 0.3f

private val SPIN_PIVOT = Offset(60f, 55f)
