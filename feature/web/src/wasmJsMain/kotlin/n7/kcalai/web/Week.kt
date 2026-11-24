package n7.kcalai.web

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import n7.kcalai.model.formatWeekdayShort
import n7.kcalai.ui.KcalTheme
import n7.kcalai.webapi.WebDay
import n7.kcalai.webapi.WebDaySummary
import n7.kcalai.webapi.weekOf

/** Высота столбика полного дня: полоса недели не должна спорить с главным числом. */
private val BAR_HEIGHT = 44.dp

/**
 * Неделя открытого дня — столбиками, как график в шапке приложения. Тап по дню
 * открывает его, стрелки листают по неделе. Будущее приглушено и не нажимается:
 * записывать туда нечего.
 */
@Composable
fun WeekStrip(day: WebDay, onOpen: (Long) -> Unit) {
    val colors = KcalTheme.colors
    val week = weekOf(day.date)
    val byDate = day.week.associateBy { it.date }
    // Столбики — относительно цели, а без цели — относительно самого сытого дня недели.
    val scale = (day.week.mapNotNull { it.goalKcal }.maxOrNull() ?: day.week.maxOfOrNull { it.kcal } ?: 0).coerceAtLeast(1)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Arrow("‹", enabled = true) { onOpen(day.date - DAYS_IN_WEEK) }
        week.forEach { date ->
            DayBar(
                date = date,
                summary = byDate[date],
                scale = scale,
                selected = date == day.date,
                today = date == day.today,
                future = date > day.today,
                onClick = { onOpen(date) },
                modifier = Modifier.weight(1f),
            )
        }
        // Неделя с сегодняшним днём — последняя: дальше листать некуда.
        Arrow("›", enabled = day.today !in week) { onOpen((day.date + DAYS_IN_WEEK).coerceAtMost(day.today)) }
    }
}

@Composable
private fun DayBar(
    date: Long,
    summary: WebDaySummary?,
    scale: Int,
    selected: Boolean,
    today: Boolean,
    future: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = KcalTheme.colors
    val kcal = summary?.kcal ?: 0
    val over = summary?.goalKcal?.let { kcal > it } == true
    val fraction by animateFloatAsState((kcal.toFloat() / scale).coerceIn(0f, 1f), tween(280, easing = EaseOutStrong))

    Column(
        modifier = modifier
            .alpha(if (future) 0.35f else 1f)
            .then(
                if (future) Modifier else Modifier.press(RoundedCornerShape(10.dp), colors.text.copy(alpha = 0.06f), onClick),
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(22.dp).height(BAR_HEIGHT), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(colors.text.copy(alpha = 0.06f)))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT * fraction)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            over -> colors.error
                            selected -> colors.bubble
                            else -> colors.text.copy(alpha = 0.28f)
                        },
                    ),
            )
        }
        Text(
            formatWeekdayShort(LocalDate.fromEpochDays(date)),
            style = KcalTheme.type.caps,
            color = when {
                selected -> colors.text
                today -> colors.text2
                else -> colors.text3
            },
        )
        // Точка под сегодняшним днём: видно, где «сейчас», даже когда открыт другой.
        Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(if (today) colors.text2 else colors.bg))
    }
}

@Composable
private fun Arrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    Text(
        glyph,
        style = KcalTheme.type.title,
        color = if (enabled) colors.text2 else colors.text3.copy(alpha = 0.3f),
        modifier = Modifier
            .padding(bottom = 22.dp)
            .then(if (enabled) Modifier.press(RoundedCornerShape(10.dp), colors.text.copy(alpha = 0.06f), onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private const val DAYS_IN_WEEK = 7
