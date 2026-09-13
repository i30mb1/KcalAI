package n7.kcalai.feature.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import n7.kcalai.repositories.DaySummary

/**
 * Калории за неделю: семь столбиков и линия цели.
 *
 * Серия здесь одна, поэтому легенды нет — заголовок и есть подпись. Сегодняшний день
 * выделен акцентным цветом, остальные шесть нарисованы приглушённым шагом того же
 * цвета: это выделение одного элемента, а не семь разных категорий.
 *
 * Красить столбики по их величине («выше — темнее») было бы ошибкой: длина столбика
 * уже несёт эту информацию, и цвет, повторяющий её, тратит единственный свободный
 * канал впустую.
 *
 * Числа над столбиками не подписаны намеренно. Сегодняшнее значение стоит крупно
 * в карточке цели прямо над графиком, остальные шесть произносит TalkBack; подпись
 * у каждого столбика превратила бы график в таблицу, которую не читают.
 *
 * Конкретных цветов здесь нет и быть не может: на Android 12+ палитра берётся из
 * обоев пользователя. Роли [MaterialTheme.colorScheme] гарантируют контраст в обеих
 * темах по построению — именно поэтому берутся они, а не подобранные вручную цвета.
 */
@Composable
fun WeekChart(
    days: List<DaySummary>,
    goalKcal: Int?,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return

    val todayEpochDay = days.last().dateEpochDay
    val goal = goalKcal?.takeIf { it > 0 }

    // Масштаб включает цель, иначе её линия ушла бы за верхний край в любой день,
    // когда человек до цели не добрал, — то есть ровно тогда, когда она нужнее всего.
    val peak = maxOf(days.maxOf { it.kcal }, goal ?: 0)

    // Запас сверху обязателен, а не для красоты. Пока история пуста, цель и есть
    // максимум недели — линия встала бы вплотную к верхнему краю, и её подпись
    // ушла бы за пределы графика, на заголовок. Заодно столбик, упёршийся
    // в потолок, читается как обрезанный.
    val scale = if (peak > 0) (peak * HEADROOM).toInt() else 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = weekSummary(days, goal) },
    ) {
        Text(
            "Калории за неделю",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Высота складывается из области данных и полосы под подпись линии цели.
        // Отступом сверху её задавать нельзя: расчёты столбиков и линии идут
        // от PLOT_HEIGHT, и любое расхождение между ним и реальной областью
        // выталкивает подпись за график — на заголовок.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PLOT_HEIGHT + GOAL_LABEL_BAND),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                // Зазор в две точки — то, что разделяет соседние столбики. Обводка
                // вокруг каждого добавила бы чернил, не несущих данных.
                horizontalArrangement = Arrangement.spacedBy(2.dp),
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

            if (goal != null && scale > 0) {
                GoalLine(goal = goal, scale = scale, modifier = Modifier.align(Alignment.BottomStart))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            days.forEach { day ->
                Text(
                    formatWeekdayShort(LocalDate.ofEpochDay(day.dateEpochDay)),
                    style = MaterialTheme.typography.labelSmall,
                    // Подпись оси носит текстовый цвет, а не цвет данных: светлый
                    // акцент в роли текста нечитаем, а день опознаёт сам столбик.
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * Высота считается в точках явно, а не долей от родителя: доля вместе с ограничением
 * «не ниже двух точек» даёт разный результат в зависимости от порядка модификаторов,
 * а столбик обязан стоять ровно там, где велит число.
 *
 * Скруглён только верх: основание стоит на общей базовой линии, иначе столбики
 * разной высоты начинают казаться висящими на разных уровнях.
 */
@Composable
private fun Bar(
    kcal: Int,
    scale: Int,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction = if (scale > 0) (kcal.toFloat() / scale).coerceIn(0f, 1f) else 0f

    Box(modifier = modifier.fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .widthIn(max = MAX_BAR_WIDTH)
                .fillMaxWidth()
                // Пустой день остаётся видимой полоской: «не ел» и «дня не было»
                // должны выглядеть по-разному.
                .height((PLOT_HEIGHT * fraction).coerceAtLeast(MIN_BAR_HEIGHT))
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .background(
                    if (isToday) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
        )
    }
}

/**
 * Линия дневной цели.
 *
 * Сплошная волосяная, а не пунктир: пунктир читается как «прогноз» или
 * «недостоверно», а цель — самая достоверная величина на этом экране.
 *
 * Подпись обязательна: одинокая горизонтальная черта на графике неотличима
 * от линии сетки, и без слова рядом её смысл приходится угадывать.
 */
@Composable
private fun GoalLine(goal: Int, scale: Int, modifier: Modifier = Modifier) {
    // Масштаб всегда не меньше цели, так что линия не поднимется выше области
    // данных, а подписи над ней хватит отведённой полосы при любых числах.
    val fromBottom = (PLOT_HEIGHT * (goal.toFloat() / scale)).coerceAtMost(PLOT_HEIGHT)

    Column(
        modifier = modifier.fillMaxWidth().offset(y = -fromBottom),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            "цель $goal",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

/**
 * Сводка недели словами — для TalkBack.
 *
 * График не должен быть единственным способом узнать значения: то, что доступно
 * только глазами, недоступно части людей вообще.
 */
private fun weekSummary(days: List<DaySummary>, goalKcal: Int?): String {
    val body = days.joinToString(", ") { day ->
        "${formatWeekdayShort(LocalDate.ofEpochDay(day.dateEpochDay))} ${day.kcal}"
    }
    val goal = goalKcal?.let { ", цель $it" }.orEmpty()
    return "Калории за неделю: $body$goal"
}

/** Область данных: от неё считаются и высота столбика, и положение линии цели. */
private val PLOT_HEIGHT = 96.dp

/** Полоса над областью данных, где помещается подпись «цель N». */
private val GOAL_LABEL_BAND = 20.dp

/** Столбик не заполняет свою колонку целиком: остаток ширины — воздух. */
private val MAX_BAR_WIDTH = 24.dp

/** Чтобы день без записей не исчезал с графика совсем. */
private val MIN_BAR_HEIGHT = 2.dp

/** Пятнадцать процентов воздуха над самым высоким столбиком и над линией цели. */
private const val HEADROOM = 1.15f
