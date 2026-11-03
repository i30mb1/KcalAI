package n7.kcalai.feature.diary

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Как лента бережёт место — так же, как лучшие мессенджеры: время не занимает
 * свою строку, а встаёт в угол, в строку с концом реплики; реплики одной стороны
 * подряд стоят тесно, как одна мысль, сказанная в несколько пузырьков.
 */

/**
 * Где встать времени.
 *
 * @param inline в строку с концом реплики, чуть ниже её; иначе — строкой ниже
 * @param width ширина пузырька вместе со временем
 */
internal data class TimeSlot(val inline: Boolean, val width: Int)

/**
 * Время — в строку с концом реплики, если там хватает места; иначе строкой ниже.
 *
 * Пузырёк при этом может стать шире текста: короткой реплике время прибавляет
 * ширины, а не строку. Шире [maxWidth] пузырёк не становится никогда.
 *
 * @param lastLineRight где кончается последняя строка текста
 */
internal fun timeSlot(textWidth: Int, lastLineRight: Int, timeWidth: Int, gapPx: Int, maxWidth: Int): TimeSlot {
    val inlineWidth = maxOf(textWidth, lastLineRight + gapPx + timeWidth)
    return if (inlineWidth <= maxWidth) {
        TimeSlot(inline = true, width = inlineWidth)
    } else {
        TimeSlot(inline = false, width = maxOf(textWidth, timeWidth))
    }
}

/** Зазор между репликами одной стороны подряд. */
internal val SAME_SIDE_GAP = 4.dp

/** Зазор при смене собеседника и после разделителя дня. */
internal val SIDE_CHANGE_GAP = 10.dp

/**
 * Отступ над элементом ленты.
 *
 * Сторона — кто говорит: справа то, что записал человек, слева приложение и
 * персонажи. Подряд с одной стороны — тесно, смена стороны — просторнее: так
 * по ленте с одного взгляда видно, где кончается одна реплика и начинается ответ.
 */
internal fun gapBefore(previous: FeedItem?, item: FeedItem): Dp = when {
    previous == null -> 0.dp
    previous is FeedItem.DaySeparator || item is FeedItem.DaySeparator -> SIDE_CHANGE_GAP
    previous.outgoing == item.outgoing -> SAME_SIDE_GAP
    else -> SIDE_CHANGE_GAP
}

/** Реплика человека — справа: записанная еда и вопрос к дневнику. */
private val FeedItem.outgoing: Boolean get() = this is FeedItem.Meal || this is FeedItem.Question
