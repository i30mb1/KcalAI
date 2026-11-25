package n7.kcalai.web

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import n7.kcalai.model.TreadmillSample
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.MiniBar
import n7.kcalai.webapi.WebDay
import n7.kcalai.webapi.WebWalk
import n7.kcalai.webapi.clockLabel
import n7.kcalai.webapi.durationLabel
import n7.kcalai.webapi.kmLabel
import n7.kcalai.webapi.speedLabel
import n7.kcalai.webapi.stepsPerSecond
import n7.kcalai.webapi.stepsShown
import n7.kcalai.webapi.stepsLabel
import n7.kcalai.webapi.timerLabel

/**
 * Шаги дня поверх ленты — окошко в правом верхнем углу, которое ничего не сдвигает.
 *
 * Пока идут по дорожке, оно раскрыто само: шаги к цели, темп, путь и время этой прогулки,
 * полоса суток — когда ходили, и прогулки дня с их темпом. Стоят — от окошка остаётся
 * плашка с шагами дня, тап раскрывает его снова. Шагов нет и дорожки нет — нет и окошка.
 * Фон у окошка свой, плотный: оно читается и без стекла колонки.
 */
@Composable
fun WalkPanel(day: WebDay, live: TreadmillSample?, walking: Boolean, modifier: Modifier = Modifier) {
    val colors = KcalTheme.colors
    var opened by remember { mutableStateOf(false) }
    val any = day.steps > 0 || day.walks.isNotEmpty() || walking
    if (!any) return
    val open = walking || opened

    Box(modifier, contentAlignment = Alignment.TopEnd) {
        // Плашка и окошко — в одном углу: раскрытие растёт из плашки, а не из ниоткуда.
        AnimatedVisibility(
            visible = !open,
            enter = fadeIn(tween(PANEL_MS)),
            exit = fadeOut(tween(PANEL_MS / 2)),
        ) {
            Text(
                "${stepsLabel(day.steps)} шагов",
                style = KcalTheme.type.number,
                color = colors.carb.text,
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(PILL_DP.dp))
                    .clip(RoundedCornerShape(PILL_DP.dp))
                    .background(colors.surface)
                    .press(RoundedCornerShape(PILL_DP.dp), colors.text.copy(alpha = HOVER_ALPHA)) { opened = true }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(PANEL_MS, easing = EaseOutStrong)) + scaleIn(tween(PANEL_MS, easing = EaseOutStrong), initialScale = 0.96f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(PANEL_MS / 2)) + scaleOut(tween(PANEL_MS / 2), targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            Card(day, live, walking, onClose = { opened = false })
        }
    }
}

@Composable
private fun Card(day: WebDay, live: TreadmillSample?, walking: Boolean, onClose: () -> Unit) {
    val colors = KcalTheme.colors
    val type = KcalTheme.type
    // Пока идут, шаги бегут по одному в такт ходьбе, а не скачками раз в секунду, когда приходит итог.
    val steps = rememberTickingSteps(day.steps, live?.speedKmh ?: 0.0, walking)
    val progress by animateFloatAsState(
        if (day.stepsGoal > 0) steps.toFloat() / day.stepsGoal else 0f,
        tween(NUMBER_MS, easing = EaseOutStrong),
    )
    Column(
        Modifier
            .width(CARD_DP.dp)
            .shadow(12.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .then(if (walking) Modifier else Modifier.press(RoundedCornerShape(18.dp), colors.text.copy(alpha = HOVER_ALPHA / 2), onClose))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stepsLabel(steps), style = type.hero, color = colors.text)
            Spacer(Modifier.width(6.dp))
            Text("/ ${stepsLabel(day.stepsGoal)}", style = type.number, color = colors.text3, modifier = Modifier.padding(bottom = 4.dp))
        }
        MiniBar(progress, colors.carb.fill, height = 6.dp)
        // Идут — темп, путь и время этой прогулки прямо с дорожки; стоят — путь и время всего дня.
        // Ширина колонок одна: цифры не прыгают.
        Row(Modifier.fillMaxWidth()) {
            val now = live?.takeIf { walking }
            val distance = now?.distanceM ?: day.walks.sumOf { it.distanceM }
            val seconds = now?.elapsedS ?: day.walks.sumOf { it.elapsedS }
            val pace = now?.speedKmh ?: if (seconds > 0) distance * KMH_PER_MPS / seconds else 0.0
            Stat(speedLabel(pace), Modifier.weight(1f), TextAlign.Start)
            Stat(kmLabel(distance), Modifier.weight(1f), TextAlign.Center)
            Stat(if (walking) timerLabel(seconds) else durationLabel(seconds), Modifier.weight(1f), TextAlign.End)
        }
        if (day.walks.isNotEmpty()) {
            DayTrack(day.walks, walking)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                day.walks.asReversed().take(WALKS_SHOWN).forEachIndexed { i, walk -> WalkRow(walk, live = walking && i == 0) }
            }
        }
    }
}

/**
 * Шаги дня, бегущие по одному: между итогами с телефона — в темпе ходьбы, свежий итог догоняется
 * плавно, назад число не идёт (см. [stepsShown]). Стоят — точный итог. Большой разрыв (другой день,
 * страницу открыли посреди прогулки) — сразу к итогу, без пробежки через тысячи.
 */
@Composable
private fun rememberTickingSteps(target: Int, speedKmh: Double, walking: Boolean): Int {
    var shown by remember { mutableDoubleStateOf(target.toDouble()) }
    val latest by rememberUpdatedState(target)
    val speed by rememberUpdatedState(speedKmh)
    LaunchedEffect(walking) {
        if (!walking) return@LaunchedEffect
        shown = latest.toDouble()
        var lastFrame = -1L
        var seen = -1
        var seenAt = 0L
        while (true) {
            withFrameMillis { now ->
                val goal = latest
                if (goal != seen) {
                    seen = goal
                    seenAt = now
                    if (goal - shown > SNAP_STEPS || shown - goal > SNAP_STEPS) shown = goal.toDouble()
                }
                val frame = if (lastFrame < 0) 0L else now - lastFrame
                lastFrame = now
                shown = stepsShown(shown, goal, now - seenAt, stepsPerSecond(speed), frame)
            }
        }
    }
    return if (walking) shown.toInt() else target
}

@Composable
private fun Stat(text: String, modifier: Modifier, align: TextAlign) {
    Text(text, style = KcalTheme.type.number, color = KcalTheme.colors.text2, textAlign = align, modifier = modifier)
}

/** Сутки полосой: где ходили — зелёным; идущая прогулка — ярче. Подписи — шесть, двенадцать, восемнадцать. */
@Composable
private fun DayTrack(walks: List<WebWalk>, walking: Boolean) {
    val colors = KcalTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Canvas(Modifier.fillMaxWidth().height(TRACK_DP.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(colors.text.copy(alpha = 0.08f), cornerRadius = r)
            walks.forEachIndexed { i, walk ->
                val from = size.width * walk.startMinute / DAY_MINUTES
                val to = maxOf(from + MIN_SEGMENT_PX, size.width * walk.endMinute / DAY_MINUTES)
                val fill = if (walking && i == walks.lastIndex) colors.carb.fill else colors.carb.fill.copy(alpha = 0.7f)
                drawRoundRect(fill, topLeft = Offset(from, 0f), size = Size(to - from, size.height), cornerRadius = r)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (hour in TRACK_HOURS) Text(hour, style = KcalTheme.type.time, color = colors.text3)
        }
    }
}

/** «18:20–18:52 · 32 мин · 2,14 км · 4,0 км/ч» — идущая отмечена точкой. */
@Composable
private fun WalkRow(walk: WebWalk, live: Boolean) {
    val colors = KcalTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (live) colors.carb.fill else colors.text.copy(alpha = 0f)))
        Spacer(Modifier.width(6.dp))
        Text("${clockLabel(walk.startMinute)}–${clockLabel(walk.endMinute)}", style = KcalTheme.type.number, color = colors.text, modifier = Modifier.weight(1f))
        Text(
            "${durationLabel(walk.elapsedS)} · ${kmLabel(walk.distanceM)} · ${speedLabel(walk.avgSpeedKmh)}",
            style = KcalTheme.type.time,
            color = colors.text2,
        )
    }
}

private const val PANEL_MS = 240

/** Разрыв больше — не пробегать шаги, а сразу показать итог. */
private const val SNAP_STEPS = 300
private const val CARD_DP = 300
private const val PILL_DP = 16
private const val TRACK_DP = 8
private const val WALKS_SHOWN = 4
private const val DAY_MINUTES = 24 * 60
private const val MIN_SEGMENT_PX = 3f

private const val KMH_PER_MPS = 3.6

/** Подписи под полосой суток — по краям и четвертям. */
private val TRACK_HOURS = listOf("00", "06", "12", "18", "24")
