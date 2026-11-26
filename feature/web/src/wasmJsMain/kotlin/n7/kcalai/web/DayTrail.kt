package n7.kcalai.web

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import n7.kcalai.model.Mascot
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.mascot.MascotFigure
import n7.kcalai.ui.mascot.MascotPose
import n7.kcalai.ui.mascot.rememberMascotPuppet

/** Дорога дня построенного мира: цель шагов и земли по порядку. */
internal data class DayRoad(val goal: Int, val lands: List<String>)

/** Дорогу называет мир прогулки (walk/src/main.js); пока он не построен или земля одна — `null`. */
@Composable
internal fun rememberDayRoad(): DayRoad? {
    var road by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            road = walkRoute()
            delay(ROAD_POLL_MS)
        }
    }
    val parts = road.split('|')
    val goal = parts.first().toIntOrNull() ?: return null
    return DayRoad(goal, parts.drop(1)).takeIf { it.lands.size > 1 }
}

/** «цель|земля|земля…»; пусто — мира ещё нет. */
private fun walkRoute(): String =
    js("(window.KcalRoute ? window.KcalRoute.goal + '|' + window.KcalRoute.lands.join('|') : '')")

/**
 * Трек дня, как в игре: снизу старт, сверху флажок цели, земли — цветными участками с названиями, по
 * тропинке идёт, покачиваясь, Зёрнышко. Идут по-настоящему ([walking]) — шагает бодрее.
 */
@Composable
internal fun DayTrail(road: DayRoad, steps: Int, walking: Boolean) {
    val colors = KcalTheme.colors
    val progress by animateFloatAsState((steps.toFloat() / road.goal).coerceIn(0f, 1f), tween(TRAIL_MOVE_MS))
    val swing by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(if (walking) WALK_SWING_MS else IDLE_SWING_MS, easing = LinearEasing), RepeatMode.Restart),
    )
    val segment = SEGMENT_DP.dp
    val height = segment * road.lands.size
    Box(Modifier.fillMaxWidth().padding(top = HERO_DP.dp / 2).height(height)) {
        Canvas(Modifier.size(width = TRACK_DP.dp, height = height).offset(x = TRACK_LEFT_DP.dp)) {
            val radius = CornerRadius(size.width / 2)
            val shape = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius)) }
            val band = size.height / road.lands.size
            clipPath(shape) {
                road.lands.forEachIndexed { index, land ->
                    val top = size.height - band * (index + 1)
                    val color = landColor(land)
                    drawRect(color, Offset(0f, top), Size(size.width, band))
                    // Пройденное — в полный цвет, впереди — приглушено.
                    val ahead = (size.height * (1f - progress) - top).coerceIn(0f, band)
                    if (ahead > 0f) drawRect(Color.White.copy(alpha = AHEAD_VEIL), Offset(0f, top), Size(size.width, ahead))
                }
            }
            // Тропинка стежками — по ней и идут.
            drawLine(
                Color.White.copy(alpha = 0.85f),
                Offset(size.width / 2, size.height - size.width / 2),
                Offset(size.width / 2, size.width / 2),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 8.dp.toPx())),
            )
            drawPath(shape, colors.text.copy(alpha = OUTLINE), style = Stroke(2.dp.toPx()))
        }
        val here = (progress * road.lands.size).toInt().coerceAtMost(road.lands.lastIndex)
        road.lands.forEachIndexed { index, land ->
            Box(
                Modifier.align(Alignment.BottomStart).offset(x = LABEL_LEFT_DP.dp, y = -segment * index).height(segment),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    land,
                    style = KcalTheme.type.label,
                    color = if (index == here) colors.text else colors.text3,
                )
            }
        }
        Text("⚑", style = KcalTheme.type.number, color = colors.steps.text, modifier = Modifier.align(Alignment.TopStart).offset(x = FLAG_LEFT_DP.dp, y = -FLAG_UP_DP.dp))
        // Зёрнышко: переваливается с ноги на ногу и подпрыгивает на каждом шаге.
        val puppet = rememberMascotPuppet(Mascot.GRAIN)
        MascotFigure(
            puppet = puppet,
            pose = MascotPose.HOME,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = HERO_LEFT_DP.dp, y = -(height * progress) + HERO_FOOT_DP.dp)
                .size(width = HERO_DP.dp, height = (HERO_DP * 5 / 6).dp)
                .graphicsLayer {
                    rotationZ = sin(swing) * if (walking) WALK_TILT else IDLE_TILT
                    translationY = -abs(sin(swing)) * (if (walking) WALK_BOB_DP else IDLE_BOB_DP).dp.toPx()
                },
        )
    }
}

/** Цвет участка — по короткому имени земли (walk/kcal/scenes.js). */
private fun landColor(land: String): Color = when (land) {
    "Поле" -> Color(0xFFF2C14E)
    "Осень" -> Color(0xFFE8894A)
    "Пустыня" -> Color(0xFFE9CF94)
    "Молоко" -> Color(0xFFA9D8F0)
    "Снег" -> Color(0xFFD7E6EF)
    "Сад" -> Color(0xFFF4A9C0)
    else -> Color(0xFF6FB27A)
}

private const val ROAD_POLL_MS = 2_000L
private const val TRAIL_MOVE_MS = 900
private const val WALK_SWING_MS = 620
private const val IDLE_SWING_MS = 2_400
private const val WALK_TILT = 11f
private const val IDLE_TILT = 4f
private const val WALK_BOB_DP = 5
private const val IDLE_BOB_DP = 2
private const val SEGMENT_DP = 52
private const val TRACK_DP = 30
private const val TRACK_LEFT_DP = 20
private const val HERO_DP = 56
private const val HERO_LEFT_DP = 7
private const val HERO_FOOT_DP = 10
private const val LABEL_LEFT_DP = 78
private const val FLAG_LEFT_DP = 56
private const val FLAG_UP_DP = 12
private const val AHEAD_VEIL = 0.55f
private const val OUTLINE = 0.18f
