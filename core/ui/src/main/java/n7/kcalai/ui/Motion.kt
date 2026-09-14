package n7.kcalai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Пружина появления: лёгкий перелёт и быстрое успокоение.
 *
 * Одна на все реплики и чипсы намеренно — два разных характера движения на одном
 * экране читались бы как два разных приложения.
 */
val PopSpring: SpringSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

/**
 * Пружинный вход: элемент вырастает из точки [origin], поднимаясь на [lift].
 *
 * Только вход. Уход анимировать нельзя: уходящий элемент дорисовывает старые
 * данные, и на дне ленты это оборачивается репликой с прошлым запросом.
 *
 * [enabled] читается один раз, при первом появлении: элемент, который встал
 * на экран без анимации, не должен подпрыгнуть задним числом, когда условие
 * поменялось.
 */
@Composable
fun Modifier.popIn(
    origin: TransformOrigin = TransformOrigin.Center,
    lift: Dp = 0.dp,
    enabled: Boolean = true,
): Modifier {
    val animate = remember { enabled }
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) progress.animateTo(1f, PopSpring)
    }
    if (!animate) return this

    val liftPx = with(LocalDensity.current) { lift.toPx() }
    return graphicsLayer {
        val p = progress.value
        val scale = 0.85f + 0.15f * p
        scaleX = scale
        scaleY = scale
        alpha = p.coerceIn(0f, 1f)
        translationY = liftPx * (1f - p)
        transformOrigin = origin
    }
}
