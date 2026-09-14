package n7.kcalai.feature.diary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import n7.kcalai.repositories.DaySummary
import n7.kcalai.ui.KcalTheme

/**
 * Калории за неделю: семь столбиков и линия цели.
 *
 * Серия одна, поэтому легенды нет. Сегодняшний день выделен цветом пузырька,
 * остальные шесть нарисованы цветом разделителей: это выделение одного элемента,
 * а не семь разных категорий. Красить столбики по их величине («выше — темнее»)
 * было бы ошибкой — длина уже несёт эту информацию, и цвет, повторяющий её,
 * тратит единственный свободный канал впустую.
 *
 * Линия цели **ступенчатая**, и это не украшение: цель версионируется датой,
 * у каждого дня своя. Одна горизонтальная черта поверх недели, в которой цель
 * менялась, — это ложь про прошлые дни.
 */
@Composable
fun WeekChart(days: List<DaySummary>, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return

    val colors = KcalTheme.colors
    val todayEpochDay = days.last().dateEpochDay
    val todayGoal = days.last().goalKcal

    // Масштаб включает цели, иначе линия ушла бы за верхний край в любой день,
    // когда человек до цели не добрал, — то есть ровно тогда, когда она нужнее.
    val peak = maxOf(
        days.maxOf { it.kcal },
        days.maxOfOrNull { it.goalKcal ?: 0 } ?: 0,
    )

    // Запас сверху обязателен, а не для красоты: пока история пуста, цель и есть
    // максимум недели, и линия встала бы вплотную к верхнему краю, а её подпись
    // ушла бы за пределы графика.
    val scale = if (peak > 0) (peak * HEADROOM).toInt() else 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = weekSummary(days) },
    ) {
        Box(Modifier.fillMaxWidth().height(PLOT_HEIGHT + GOAL_LABEL_BAND)) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { day ->
                    Bar(
                        kcal = day.kcal,
                        scale = scale,
                        isToday = day.dateEpochDay == todayEpochDay,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (scale > 0 && days.any { it.goalKcal != null }) {
                GoalSteps(days, scale, Modifier.align(Alignment.BottomStart))
            }

            // Подпись стоит у правого края и говорит про цель сегодняшнего дня:
            // ступеней может быть несколько, а подписать их все значит завалить
            // график числами, которых никто не просил.
            if (todayGoal != null) {
                Text(
                    "цель $todayGoal",
                    style = KcalTheme.type.time,
                    color = colors.text3,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(y = -(PLOT_HEIGHT * (todayGoal.toFloat() / scale)).coerceAtMost(PLOT_HEIGHT)),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            days.forEach { day ->
                Text(
                    formatWeekdayShort(LocalDate.ofEpochDay(day.dateEpochDay)),
                    style = KcalTheme.type.time,
                    color = if (day.dateEpochDay == todayEpochDay) colors.text2 else colors.text3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Один столбик.
 *
 * Высота считается в точках явно, а не долей от родителя: доля вместе с оговоркой
 * «не ниже двух точек» даёт разный результат в зависимости от порядка модификаторов,
 * а столбик обязан стоять ровно там, где велит число.
 */
@Composable
private fun Bar(kcal: Int, scale: Int, isToday: Boolean, modifier: Modifier = Modifier) {
    val colors = KcalTheme.colors
    val fraction = if (scale > 0) (kcal.toFloat() / scale).coerceIn(0f, 1f) else 0f

    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                // Пустой день остаётся видимой полоской: «не ел» и «дня не было»
                // должны выглядеть по-разному.
                .height((PLOT_HEIGHT * fraction).coerceAtLeast(MIN_BAR_HEIGHT))
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .background(if (isToday) colors.bubble else colors.line)
        )
    }
}

/**
 * Ступенчатая линия целей.
 *
 * Рисуется одним `Canvas` поверх столбиков: ступеней столько, сколько раз цель
 * менялась за неделю, и собирать их из отдельных `Box` значило бы считать одну
 * и ту же геометрию дважды — в раскладке и в голове.
 *
 * Дни без цели остаются без линии: пустое место здесь — это «цели не было»,
 * и дотягивать до них соседнюю черту значило бы её придумать.
 */
@Composable
private fun GoalSteps(days: List<DaySummary>, scale: Int, modifier: Modifier = Modifier) {
    val color = KcalTheme.colors.text3

    Canvas(modifier.fillMaxWidth().height(PLOT_HEIGHT)) {
        val gap = GOAL_STEP_GAP.toPx()
        val slot = (size.width - gap * (days.size - 1)) / days.size
        val thickness = GOAL_LINE.toPx()

        days.forEachIndexed { index, day ->
            val goal = day.goalKcal ?: return@forEachIndexed
            val left = index * (slot + gap)
            val y = size.height - size.height * (goal.toFloat() / scale).coerceIn(0f, 1f)

            drawLine(
                color = color,
                start = Offset(left, y),
                end = Offset(left + slot, y),
                strokeWidth = thickness,
            )

            // Вертикальная перемычка между разными целями: без неё две ступени
            // читаются как две несвязанные черты, а не как одна изменившаяся цель.
            val previous = days.getOrNull(index - 1)?.goalKcal
            if (previous != null && previous != goal) {
                val previousY =
                    size.height - size.height * (previous.toFloat() / scale).coerceIn(0f, 1f)
                drawLine(
                    color = color,
                    start = Offset(left, previousY),
                    end = Offset(left, y),
                    strokeWidth = thickness,
                )
            }
        }
    }
}

/**
 * Сводка недели словами — для TalkBack.
 *
 * График не должен быть единственным способом узнать значения: то, что доступно
 * только глазами, недоступно части людей вообще.
 */
private fun weekSummary(days: List<DaySummary>): String {
    val body = days.joinToString(", ") { day ->
        val weekday = formatWeekdayShort(LocalDate.ofEpochDay(day.dateEpochDay))
        val goal = day.goalKcal?.let { " из $it" }.orEmpty()
        "$weekday ${day.kcal}$goal"
    }
    return "Калории за неделю: $body"
}

/** Область данных: от неё считаются и высота столбика, и положение линии цели. */
private val PLOT_HEIGHT = 42.dp

/** Полоса над областью данных, где помещается подпись «цель N». */
private val GOAL_LABEL_BAND = 14.dp

/** Зазор между столбиками. Он же — разрыв между ступенями линии цели. */
private val GOAL_STEP_GAP = 6.dp

private val GOAL_LINE = 1.dp

/** Чтобы день без записей не исчезал с графика совсем. */
private val MIN_BAR_HEIGHT = 2.dp

/** Пятнадцать процентов воздуха над самым высоким столбиком и над линией цели. */
private const val HEADROOM = 1.15f
