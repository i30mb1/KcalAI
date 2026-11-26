package n7.kcalai.feature.diary

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.drop

/**
 * Дни — страницами: соседняя переписка тянется следом за пальцем и сама защёлкивается на место.
 * Страница перевалила пальцем за середину — щелчок вибрацией, и неделя наверху сразу переключается,
 * не дожидаясь защёлкивания. День, выбранный тапом по неделе или
 * «назад», страницы доезжают сами. Дни — те же семь, что на графике недели; вертикальная прокрутка
 * ленты листанию не мешает.
 */
@Composable
internal fun DayPager(
    date: Long,
    today: Long,
    onSelectDay: (Long) -> Unit,
    modifier: Modifier = Modifier,
    page: @Composable (day: Long) -> Unit,
) {
    val first = today - (PAGES - 1)
    fun pageOf(day: Long) = (day - first).toInt().coerceIn(0, PAGES - 1)
    val pager = rememberPagerState(initialPage = pageOf(date)) { PAGES }
    val shown by rememberUpdatedState(date)
    val select by rememberUpdatedState(onSelectDay)
    val haptics = LocalHapticFeedback.current
    val turn = remember { DayTurn() }

    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) turn.grabbed() }
    }
    LaunchedEffect(pager, first) {
        snapshotFlow { pager.currentPage }.drop(1).collect { index ->
            val day = first + index
            if (turn.crossed(day, shown)) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                select(day)
            }
        }
    }
    LaunchedEffect(date, first) {
        if (turn.opened(date, pager.isScrollInProgress) && pageOf(date) != pager.currentPage) {
            pager.animateScrollToPage(pageOf(date))
        }
    }

    HorizontalPager(
        state = pager,
        modifier = modifier,
        // Соседние страницы готовы заранее: за пальцем тянется переписка, а не пустота.
        beyondViewportPageCount = 1,
        // Хватает увести на четверть — дальше защёлкнется сама.
        flingBehavior = PagerDefaults.flingBehavior(pager, snapPositionalThreshold = SNAP_SHARE),
        key = { first + it },
    ) { index -> page(first + index) }
}

/**
 * Кто листает дни. Пальцем — открытый день меняется вслед за страницей; тапом по неделе или «назад» —
 * наоборот, страницы едут за днём.
 */
internal class DayTurn {
    private var byHand = false

    /** День, на который перелистнули и ещё не получили: пока он в пути, страницы назад не тянет. */
    private var asked: Long? = null

    /** Палец взялся за страницы. */
    fun grabbed() {
        byHand = true
    }

    /**
     * Страница дня [day] перевалила за середину, открыт пока [shown].
     *
     * @return листают пальцем — щёлкнуть и открыть [day]
     */
    fun crossed(day: Long, shown: Long): Boolean {
        if (!byHand) return false
        asked = day.takeIf { it != shown }
        return true
    }

    /**
     * Открытым стал день [date].
     *
     * @param scrolling страницы сейчас едут
     * @return везти ли страницы к нему самим
     */
    fun opened(date: Long, scrolling: Boolean): Boolean {
        if (asked != null) {
            if (date == asked) asked = null
            return false
        }
        if (byHand && scrolling) return false
        byHand = false
        return true
    }
}

private const val PAGES = 7
private const val SNAP_SHARE = 0.25f
