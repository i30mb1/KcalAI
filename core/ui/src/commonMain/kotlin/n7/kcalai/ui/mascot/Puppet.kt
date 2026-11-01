package n7.kcalai.ui.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import n7.kcalai.model.Mascot

/** Что персонаж умеет сделать. Каждое — конечное: сыграл и вернулся в покой. */
enum class MascotAct {
    /** Желейная тряска от тычка. */
    SHAKE,

    /** Прыжок с приседанием и растяжкой. */
    HOP,

    /** Кивок: «записал». */
    NOD,

    /** Фирменный трюк: Яйцо крутит сальто, Масло скользит, Зёрнышко делает кульбит с искрами. */
    TRICK,

    /** Голова кружится. */
    DIZZY,

    /** Зевок с потягиванием. */
    YAWN,

    /** Вздрогнул и подскочил: разбудили. */
    STARTLE,

    /** Подтаял от удовольствия. Масло — буквально. */
    MELT,

    /** Два прыжка и искры. */
    CELEBRATE,

    /** Отвернулся и дуется. */
    SULK,

    /** Жмурится и краснеет: гладят. */
    BLISS,
}

/**
 * Кукла одного персонажа: всё, что в нём сейчас движется.
 *
 * Состояние вынесено из рисунка, чтобы им управляла сцена: она знает, где палец,
 * что записали и не пора ли спать. Рисунок только читает значения и перерисовывается —
 * без перекомпоновки экрана.
 *
 * Действия идут по одному: новое прерывает прошлое, и прерванное успевает
 * вернуть персонажа в покой. Дыхание, взмахи, взгляд и моргание живут отдельно
 * и с действиями не спорят.
 */
@Stable
class MascotPuppet internal constructor(val mascot: Mascot) {

    internal val shake = Animatable(1f)
    internal val breath = Animatable(0f)
    internal val wave = Animatable(0f)
    internal val hop = Animatable(0f)
    internal val squash = Animatable(0f)
    internal val spin = Animatable(0f)
    internal val slide = Animatable(0f)
    internal val turn = Animatable(1f)
    internal val wobble = Animatable(1f)
    internal val blinking = Animatable(0f)
    internal val gaze = Animatable(Offset.Zero, Offset.VectorConverter)
    internal val emoteProgress = Animatable(1f)

    /** Лицо прямо сейчас. */
    var face: MascotFace by mutableStateOf(MascotFace.CALM)
        private set

    /** Что вылетает из персонажа. */
    var emote: Emote? by mutableStateOf(null)
        private set

    /** К какому лицу персонаж возвращается после действия: спит он или бодрствует. */
    private var rest: MascotFace by mutableStateOf(MascotFace.CALM)

    val asleep: Boolean get() = rest == MascotFace.ASLEEP

    private val acting = MutatorMutex()

    suspend fun play(act: MascotAct) = acting.mutate {
        try {
            perform(act)
        } finally {
            withContext(NonCancellable) { settle() }
        }
    }

    /**
     * Его держат пальцем: лицо [face], пока не отпустят, а у испуганного — капля у виска.
     *
     * Длится сколько угодно, но кадров не стоит: ничего не анимирует, просто держит
     * лицо. Отпустили — сцена отменяет держание или запускает следующее действие.
     */
    suspend fun hold(face: MascotFace): Nothing = acting.mutate {
        try {
            this.face = face
            emote = if (face == MascotFace.WORRIED) Emote.DROP else null
            emoteProgress.snapTo(0f)
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { settle() }
        }
    }

    /** Засыпает: глаза закрыты, «з-з-з» уплывают и замирают лесенкой. */
    suspend fun sleep() = acting.mutate {
        withContext(NonCancellable) { settle() }
        rest = MascotFace.ASLEEP
        face = MascotFace.ASLEEP
        emote = Emote.ZZZ
        emoteProgress.snapTo(0f)
        emoteProgress.animateTo(1f, tween(SLEEP_DRIFT_MS, easing = LinearEasing))
    }

    /** Просыпается. [startled] — разбудили касанием: вздрагивает. */
    suspend fun wake(startled: Boolean) {
        rest = MascotFace.CALM
        if (startled) {
            play(MascotAct.STARTLE)
        } else {
            acting.mutate { withContext(NonCancellable) { settle() } }
        }
    }

    /** Моргнуть. Закрытые и дугой глаза не моргают. */
    suspend fun blink() {
        if (face !in OPEN_EYED) return
        blinking.animateTo(1f, tween(BLINK_CLOSE_MS))
        blinking.animateTo(0f, tween(BLINK_OPEN_MS))
    }

    /** Посмотреть: [target] — направление, компоненты −1…1; ноль — прямо на человека. */
    suspend fun lookAt(target: Offset) {
        val length = hypot(target.x, target.y)
        val aim = if (length > 1f) target / length else target
        gaze.animateTo(aim, spring(dampingRatio = 0.85f, stiffness = 260f))
    }

    suspend fun breathe(times: Int) = breath.swing(times, mascot.art.breathMs)

    suspend fun wave(times: Int) = wave.swing(times, WAVE_MS)

    /** Стоит ли он неподвижно. Для тестов: после любого действия — да. */
    internal fun isStill(): Boolean =
        shake.value == 1f && hop.value == 0f && squash.value == 0f && spin.value == 0f &&
            slide.value == 0f && turn.value == 1f && wobble.value == 1f

    private suspend fun perform(act: MascotAct) {
        when (act) {
            MascotAct.SHAKE -> {
                if (!asleep) face = MascotFace.HAPPY
                shake.snapTo(0f)
                shake.animateTo(1f, tween(SHAKE_MS, easing = LinearEasing))
            }
            MascotAct.HOP -> {
                face = MascotFace.HAPPY
                jump(1f)
            }
            MascotAct.NOD -> repeat(2) {
                squash.animateTo(0.09f, tween(120))
                squash.animateTo(0f, tween(140))
            }
            MascotAct.TRICK -> trick()
            MascotAct.DIZZY -> withEmote(Emote.STARS, DIZZY_MS) {
                face = MascotFace.DIZZY
                wobble.snapTo(0f)
                wobble.animateTo(1f, tween(DIZZY_MS, easing = LinearEasing))
            }
            MascotAct.YAWN -> {
                face = MascotFace.YAWN
                squash.animateTo(-0.09f, tween(450))
                delay(350)
                squash.animateTo(0f, tween(450))
            }
            MascotAct.STARTLE -> {
                face = MascotFace.SURPRISED
                squash.snapTo(-0.06f)
                hop.animateTo(0.35f, tween(120, easing = FastOutSlowInEasing))
                hop.animateTo(0f, tween(140, easing = FastOutLinearInEasing))
                squash.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f))
                delay(250)
            }
            MascotAct.MELT -> withEmote(Emote.HEART, 1_300) {
                face = MascotFace.BLISS
                squash.animateTo(0.2f, tween(500))
                delay(300)
                squash.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 200f))
            }
            MascotAct.CELEBRATE -> withEmote(Emote.SPARKLES, 1_200) {
                face = MascotFace.HAPPY
                jump(1f)
                jump(0.7f)
            }
            MascotAct.SULK -> {
                face = MascotFace.GRUMPY
                turn.animateTo(-1f, tween(220))
                delay(1_500)
                turn.animateTo(1f, tween(220))
            }
            MascotAct.BLISS -> withEmote(Emote.HEART, 1_500) {
                face = MascotFace.BLISS
                repeat(2) {
                    squash.animateTo(0.06f, tween(200))
                    squash.animateTo(-0.03f, tween(200))
                }
                squash.animateTo(0f, tween(200))
                delay(500)
            }
        }
    }

    /** Трюк у каждого свой: так их и различают, не глядя на цвет. */
    private suspend fun trick() {
        when (mascot) {
            Mascot.EGG -> {
                face = MascotFace.HAPPY
                coroutineScope {
                    launch { spin.animateTo(360f, tween(560, easing = FastOutSlowInEasing)) }
                    jump(1.2f)
                }
            }
            Mascot.BUTTER -> {
                face = MascotFace.BLISS
                slide.animateTo(-1f, tween(260))
                coroutineScope {
                    launch { spin.animateTo(-8f, tween(300)) }
                    slide.animateTo(1f, tween(380))
                }
                coroutineScope {
                    launch { spin.animateTo(0f, tween(240)) }
                    slide.animateTo(0f, tween(240))
                }
            }
            Mascot.GRAIN -> withEmote(Emote.SPARKLES, 900) {
                face = MascotFace.HAPPY
                coroutineScope {
                    launch { spin.animateTo(-360f, tween(560, easing = FastOutSlowInEasing)) }
                    jump(1.4f)
                }
            }
        }
    }

    /** Присел, взлетел вытянувшись, упал и пружинит. */
    private suspend fun jump(height: Float) {
        squash.animateTo(0.12f, tween(90))
        coroutineScope {
            launch {
                squash.animateTo(-0.1f, tween(120))
                squash.animateTo(0f, tween(160))
            }
            hop.animateTo(height, tween(220, easing = FastOutSlowInEasing))
        }
        hop.animateTo(0f, tween(200, easing = FastOutLinearInEasing))
        squash.snapTo(0.14f)
        squash.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 400f))
    }

    private suspend fun withEmote(kind: Emote, durationMs: Int, body: suspend () -> Unit) = coroutineScope {
        emote = kind
        emoteProgress.snapTo(0f)
        launch { emoteProgress.animateTo(1f, tween(durationMs, easing = LinearEasing)) }
        body()
    }

    /** Покой: тело на месте, лицо — к своему обычному, спящий снова спит. */
    private suspend fun settle() {
        shake.snapTo(1f)
        hop.snapTo(0f)
        squash.snapTo(0f)
        spin.snapTo(0f)
        slide.snapTo(0f)
        turn.snapTo(1f)
        wobble.snapTo(1f)
        face = rest
        emote = if (asleep) Emote.ZZZ else null
        emoteProgress.snapTo(1f)
    }

    private companion object {
        const val SHAKE_MS = 620
        const val DIZZY_MS = 1_600
        const val WAVE_MS = 550
        const val SLEEP_DRIFT_MS = 2_600
        const val BLINK_CLOSE_MS = 70
        const val BLINK_OPEN_MS = 100

        /** Лица с открытыми глазами-точками: только они и моргают. */
        val OPEN_EYED = setOf(MascotFace.CALM, MascotFace.SURPRISED, MascotFace.WORRIED, MascotFace.GRUMPY)
    }
}

@Composable
fun rememberMascotPuppet(mascot: Mascot): MascotPuppet = remember(mascot) { MascotPuppet(mascot) }

/** Туда и обратно [times] раз — и в покой. */
private suspend fun Animatable<Float, *>.swing(times: Int, halfMs: Int) {
    repeat(times) {
        animateTo(1f, tween(halfMs, easing = FastOutSlowInEasing))
        animateTo(0f, tween(halfMs, easing = FastOutSlowInEasing))
    }
}
