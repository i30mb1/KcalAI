package n7.kcalai.web

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import n7.kcalai.model.formatWeekRange
import n7.kcalai.model.formatWeekdayShort
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.MiniBar
import n7.kcalai.webapi.WebDay
import n7.kcalai.webapi.WebDaySummary
import n7.kcalai.webapi.weekOf

/** Высота столбика полного дня: неделя читается, но не спорит с главным числом. */
private val BAR_HEIGHT = 64.dp

/**
 * Неделя открытого дня — во всю ширину, как график в шапке приложения: над каждым
 * столбиком его калории, пунктир — цель дня. Тап по дню открывает его, стрелки листают
 * по неделе. Будущее приглушено и не нажимается: записывать туда нечего.
 */
@Composable
fun WeekStrip(day: WebDay, onOpen: (Long) -> Unit) {
    val week = weekOf(day.date)
    val byDate = day.week.associateBy { it.date }
    // Масштаб — по цели и по самому сытому дню: перебор виден, а не обрезан краем.
    val scale = maxOf(
        day.week.mapNotNull { it.goalKcal }.maxOrNull() ?: 0,
        day.week.maxOfOrNull { it.kcal } ?: 0,
    ).coerceAtLeast(1)
    // Полоски шагов — только у недели, где ходили: у остальных место под них не нужно.
    val steps = day.week.any { it.steps > 0 }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    // Какая это неделя — датами, между стрелками, которые её листают.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Arrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Неделя назад", enabled = true) { onOpen(day.date - DAYS_IN_WEEK) }
        Crossfade(
            targetState = formatWeekRange(LocalDate.fromEpochDays(week.first), LocalDate.fromEpochDays(week.last)),
            animationSpec = tween(NUMBER_MS),
            modifier = Modifier.weight(1f),
            label = "week",
        ) { range ->
            Text(
                range,
                style = KcalTheme.type.caps,
                color = KcalTheme.colors.text2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Неделя с сегодняшним днём — последняя: дальше листать некуда.
        Arrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Неделя вперёд", enabled = day.today !in week) {
            onOpen((day.date + DAYS_IN_WEEK).coerceAtMost(day.today))
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            week.forEach { date ->
                DayColumn(
                    date = date,
                    summary = byDate[date],
                    scale = scale,
                    selected = date == day.date,
                    today = date == day.today,
                    future = date > day.today,
                    onClick = { onOpen(date) },
                    modifier = Modifier.weight(1f),
                    stepsGoal = day.stepsGoal.takeIf { steps },
                )
            }
        }
    }
    }
}

@Composable
private fun DayColumn(
    date: Long,
    summary: WebDaySummary?,
    scale: Int,
    selected: Boolean,
    today: Boolean,
    future: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    stepsGoal: Int?,
) {
    val colors = KcalTheme.colors
    val kcal = summary?.kcal ?: 0
    val goal = summary?.goalKcal
    val over = goal != null && kcal > goal
    val fraction by animateFloatAsState((kcal.toFloat() / scale).coerceIn(0f, 1f), tween(NUMBER_MS, easing = EaseOutStrong))
    // У будущего дня цели не видно: пунктир над пустым днём — шум, а не ориентир.
    val goalLine = goal?.takeUnless { future }?.let { (it.toFloat() / scale).coerceIn(0f, 1f) }

    Column(
        modifier = modifier
            .alpha(if (future) 0.35f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.text.copy(alpha = 0.06f) else colors.bg)
            .then(if (future) Modifier else Modifier.press(RoundedCornerShape(12.dp), colors.text.copy(alpha = 0.05f), onClick))
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Калории дня — над столбиком; пустой день без нуля: «0» над каждым будущим днём — шум.
        Text(
            if (kcal > 0) "$kcal" else " ",
            style = KcalTheme.type.time,
            color = when {
                over -> colors.error
                selected -> colors.text
                else -> colors.text3
            },
        )
        Box(
            Modifier
                .widthIn(max = 40.dp)
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .drawBehind {
                    // Цель дня — пунктиром поперёк столбика: видно, сколько не хватило или сколько лишнего.
                    goalLine?.let { line ->
                        val y = size.height * (1f - line)
                        drawLine(
                            colors.text3,
                            Offset(0f, y),
                            Offset(size.width, y),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(colors.text.copy(alpha = 0.05f)))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT * fraction)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            over -> colors.error
                            selected -> colors.bubble
                            else -> colors.text.copy(alpha = 0.3f)
                        },
                    ),
            )
        }
        val weekday = LocalDate.fromEpochDays(date)
        Text(
            formatWeekdayShort(weekday),
            style = KcalTheme.type.caps,
            color = when {
                selected -> colors.text
                today -> colors.text2
                else -> colors.text3
            },
        )
        // Число месяца под днём недели: «пн» прошлой недели и этой — разные дни.
        Text(
            "${weekday.day}",
            style = KcalTheme.type.time,
            color = if (selected) colors.text else colors.text3,
        )
        // Шаги дня — полоской своего цвета до цели шагов. Без шагов — пустое место той же высоты:
        // пустые полоски под каждым днём читались чёрточками-заусенцами, а место держит строку ровной.
        stepsGoal?.let { goalSteps ->
            val steps = summary?.steps ?: 0
            if (steps > 0) {
                MiniBar((steps.toFloat() / goalSteps).coerceIn(0f, 1f), colors.steps.fill, Modifier.widthIn(max = STEP_BAR_DP.dp), height = 3.dp)
            } else {
                Spacer(Modifier.height(3.dp))
            }
        }
        // Точка под сегодняшним днём: видно, где «сейчас», даже когда открыт другой.
        Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(if (today) colors.text2 else colors.text.copy(alpha = 0f)))
    }
}

@Composable
private fun Arrow(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    Icon(
        icon,
        contentDescription = description,
        tint = if (enabled) colors.text2 else colors.text3.copy(alpha = 0.3f),
        modifier = Modifier
            .then(if (enabled) Modifier.press(RoundedCornerShape(10.dp), colors.text.copy(alpha = 0.06f), onClick) else Modifier)
            .padding(6.dp)
            .size(22.dp),
    )
}

private const val DAYS_IN_WEEK = 7
private const val STEP_BAR_DP = 28
