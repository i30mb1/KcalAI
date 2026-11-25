package n7.kcalai.feature.diary

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.drop

/**
 * Дни — страницами: соседняя переписка тянется следом за пальцем и сама защёлкивается на место.
 * Перевели пальцем на другой день — короткий щелчок вибрацией. День, выбранный тапом по неделе или
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
    // Листают пальцем — тогда щелчок и новый день; доезжают сами после тапа — молча.
    var byHand by remember { mutableStateOf(false) }
    // День, на который перелистнули и ещё не получили: пока он в пути, прежний пейджер назад не тянет.
    var asked by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) byHand = true }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.drop(1).collect {
            if (byHand) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    LaunchedEffect(pager, first) {
        snapshotFlow { pager.settledPage }.drop(1).collect { settled ->
            val day = first + settled
            if (byHand && day != shown) {
                asked = day
                select(day)
            }
            byHand = false
        }
    }
    LaunchedEffect(date, first) {
        if (asked != null) {
            if (date == asked) asked = null
            return@LaunchedEffect
        }
        if (pageOf(date) != pager.currentPage) {
            byHand = false
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

private const val PAGES = 7
private const val SNAP_SHARE = 0.25f
