package n7.kcalai.web

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import kotlinx.coroutines.launch

/** Сильный ease-out: движение начинается сразу — интерфейс слышит человека без задержки. */
val EaseOutStrong = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/**
 * Нажимаемое: под пальцем или мышью чуть сжимается, под курсором — подсвечивается,
 * курсор — рука. Подсветка только от мыши: у пальца наведения нет, и ложной
 * подсветки после тапа не бывает.
 */
@Composable
fun Modifier.press(shape: Shape, hover: Color, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, tween(PRESS_MS, easing = EaseOutStrong))
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        // Поверх фона и текста: фон чипса задают после этого модификатора, и под ним подсветки не видно.
        .drawWithContent {
            drawContent()
            if (hovered) drawRect(hover)
        }
        .hoverable(source)
        .pointerHoverIcon(PointerIcon.Hand)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

/**
 * Появление: из 0.95 и прозрачности, а не из ничего — так появляются вещи и в жизни.
 * Короткое: новое не должно заставлять себя ждать.
 */
@Composable
fun Modifier.enter(): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { progress.animateTo(1f, tween(ENTER_MS, easing = EaseOutStrong)) }
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        val scale = ENTER_FROM + (1f - ENTER_FROM) * p
        scaleX = scale
        scaleY = scale
    }
}

private const val PRESSED_SCALE = 0.97f
private const val PRESS_MS = 140
private const val ENTER_MS = 220
private const val ENTER_FROM = 0.95f
