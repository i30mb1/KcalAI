package n7.kcalai.web

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import n7.kcalai.model.FiberTotal
import n7.kcalai.model.Mascot
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.dayLabel
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.Macro
import n7.kcalai.ui.MacroColors
import n7.kcalai.ui.MiniBar
import n7.kcalai.ui.SproutIcon
import n7.kcalai.ui.colors
import n7.kcalai.ui.mascot.MascotAct
import n7.kcalai.ui.mascot.MascotFigure
import n7.kcalai.ui.mascot.MascotPose
import n7.kcalai.ui.mascot.MascotPuppet
import n7.kcalai.ui.mascot.mascot
import n7.kcalai.ui.mascot.rememberMascotPuppet
import n7.kcalai.webapi.PageLine
import n7.kcalai.webapi.PageState
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WebDay
import n7.kcalai.webapi.WebMacros
import n7.kcalai.webapi.arrivals
import n7.kcalai.webapi.line
import n7.kcalai.webapi.offlineLine

/**
 * Ширина колонки. Дневник — переписка, и строки в ней не тянутся на весь монитор,
 * но и не жмутся: шапке с четырьмя плитками и неделей нужен воздух.
 */
private val COLUMN_MAX = 760.dp

/** Число калорий доезжает до нового — но быстро: это ответ на действие, а не заставка. */
internal const val NUMBER_MS = 280

/** Подсветка под курсором — едва заметная: она говорит «нажимается», а не кричит. */
internal const val HOVER_ALPHA = 0.08f

internal const val CENTIGRAMS_PER_GRAM = 100

/** Насколько приглушить неживой дневник: читается, но видно, что сейчас он не отвечает. */
private val OFFLINE_BLUR = 6.dp
private const val OFFLINE_SCRIM = 0.35f
private const val OFFLINE_MS = 300

/**
 * Страница дневника — как переписка в приложении: сверху шапка дня, ниже лента,
 * прижатая к строке ввода. Приёмы — тёмными пузырьками справа, персонажи — слева.
 */
@Composable
fun DiaryPage(link: PhoneLink) {
    val state by link.state.collectAsState()
    val colors = KcalTheme.colors
    val diary = state as? PageState.Diary
    // Хозяева макросов живут в плитках шапки: новая запись — её хозяин подпрыгивает.
    val puppets = Mascot.entries.associateWith { rememberMascotPuppet(it) }
    var seen by remember { mutableStateOf<WebDay?>(null) }
    LaunchedEffect(diary?.day) {
        val day = diary?.day ?: return@LaunchedEffect
        day.arrivals(since = seen).forEach { mascot -> launch { puppets.getValue(mascot).play(MascotAct.HOP) } }
        seen = day
    }
    LaunchedEffect(diary?.day?.owner) {
        document.title = diary?.day?.owner?.takeIf { it.isNotBlank() }?.let { "$it · дневник" } ?: "Дневник"
    }
    var settings by remember { mutableStateOf(false) }
    // Связь пропала — дневник остаётся как был, только приглушённый: видно, что он неживой.
    val offline = diary?.offline == true
    val blur by animateDpAsState(if (offline) OFFLINE_BLUR else 0.dp, tween(OFFLINE_MS, easing = EaseOutStrong))

    Box(Modifier.fillMaxSize().background(colors.bg), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = COLUMN_MAX).fillMaxSize().blur(blur)) {
            if (diary == null) {
                // До пуска на странице только разговор: код, отказ или пропавшая связь.
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    IncomingLine(state.line(), action = if (state == PageState.Denied) ("Попросить" to link::askAgain) else null)
                }
            } else {
                Header(
                    day = diary.day,
                    puppets = puppets,
                    onSettings = { settings = true },
                    onOpen = { link.send(ToPhone.Open(it)) },
                )
                Feed(diary, link, Modifier.weight(1f))
                Composer(diary, link)
            }
        }
        if (diary != null && offline) {
            OfflineOverlay(diary.offlineLine())
        } else if (settings && diary != null) {
            SettingsPanel(diary.day, link, onClose = { settings = false })
        }
    }
}

/**
 * Шапка — как у приложения: чей дневник и какой день, крупный итог с полосой до цели,
 * плитки белков, жиров, углеводов и клетчатки, неделя. Тап по итогу — к цели.
 */
@Composable
private fun Header(day: WebDay, puppets: Map<Mascot, MascotPuppet>, onSettings: () -> Unit, onOpen: (Long) -> Unit) {
    val colors = KcalTheme.colors
    val type = KcalTheme.type
    val totals = day.totals()
    val kcal by animateIntAsState(totals.kcal, tween(NUMBER_MS, easing = EaseOutStrong))
    val goal = day.goalKcal
    val over = goal != null && totals.kcal > goal
    val past = day.date < day.today
    val label = dayLabel(LocalDate.fromEpochDays(day.date), LocalDate.fromEpochDays(day.today))

    Column(
        Modifier
            .fillMaxWidth()
            // Шапка отделена от ленты тонкой линией, как в приложении: лента под ней прокручивается.
            .drawBehind { drawLine(colors.line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f) }
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Чей дневник и какой день. Прошлый день — тапом обратно в сегодня, как в шапке приложения.
            CapsLabel(
                text = listOfNotNull(day.owner.takeIf { it.isNotBlank() }, if (past) "← $label" else label).joinToString(" · "),
                color = if (past) colors.text else colors.text3,
                modifier = Modifier
                    .then(if (past) Modifier.press(RoundedCornerShape(8.dp), colors.text.copy(alpha = HOVER_ALPHA)) { onOpen(day.today) } else Modifier)
                    .padding(vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.Settings,
                contentDescription = "Настройки",
                tint = colors.text3,
                modifier = Modifier
                    .press(RoundedCornerShape(10.dp), colors.text.copy(alpha = HOVER_ALPHA), onSettings)
                    .padding(8.dp)
                    .size(20.dp),
            )
        }
        // Тап по итогу — к цели, как в приложении: подпись «задать цель» не нужна.
        Column(
            Modifier.press(RoundedCornerShape(KcalShapes.tile), colors.text.copy(alpha = HOVER_ALPHA), onSettings),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$kcal", style = type.hero, color = if (over) colors.error else colors.text)
                Text(
                    if (goal != null) "из $goal ккал" else "ккал",
                    style = type.label,
                    color = if (over) colors.error else colors.text2,
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                )
            }
            // Место под полосу есть всегда: появление цели не сдвигает плитки.
            if (goal != null) {
                val fraction by animateFloatAsState(totals.kcal.toFloat() / goal, tween(NUMBER_MS, easing = EaseOutStrong))
                MiniBar(fraction = fraction, fill = if (over) colors.error else colors.bubble, height = 6.dp)
            } else {
                Spacer(Modifier.height(6.dp))
            }
        }
        MacroTiles(totals, day.goalMacros, day.fiber(), day.fiberGoal, puppets)
        WeekStrip(day, onOpen)
    }
}

@Composable
private fun MacroTiles(
    totals: NutrimentTotals,
    goal: WebMacros?,
    fiber: FiberTotal,
    fiberGoal: Int?,
    puppets: Map<Mascot, MascotPuppet>,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple(Macro.PROTEIN, totals.protCg, goal?.prot),
            Triple(Macro.FAT, totals.fatCg, goal?.fat),
            Triple(Macro.CARB, totals.carbCg, goal?.carb),
        ).forEach { (macro, eatenCg, goalGrams) ->
            val puppet = puppets.getValue(macro.mascot)
            val scope = rememberCoroutineScope()
            Tile(macro.colors(), eatenCg, goalGrams, Modifier.weight(1f)) {
                // Тап по персонажу — он трясётся, как в приложении.
                MascotFigure(
                    puppet = puppet,
                    pose = MascotPose.HOME,
                    modifier = Modifier.size(width = 42.dp, height = 35.dp).pointerInput(puppet) {
                        detectTapGestures { scope.launch { puppet.play(MascotAct.SHAKE) } }
                    },
                )
            }
        }
        // Клетчатка — рядом с углеводами, ростком Зёрнышка: она его тема. Неизвестная
        // у части продуктов — нижняя граница «≥», а не выдуманный ноль.
        Tile(KcalTheme.colors.fiber, fiber.cg, fiberGoal, Modifier.weight(1f), lowerBound = !fiber.complete && fiber.cg > 0) {
            Box(Modifier.size(width = 42.dp, height = 35.dp), contentAlignment = Alignment.Center) {
                SproutIcon(KcalTheme.colors.fiber.text, size = 22.dp)
            }
        }
    }
}

/** Плитка: число, домик хозяина и полоса до цели. Место под полосу есть всегда. */
@Composable
private fun Tile(
    palette: MacroColors,
    eatenCg: Int,
    goalGrams: Int?,
    modifier: Modifier,
    lowerBound: Boolean = false,
    home: @Composable () -> Unit,
) {
    val type = KcalTheme.type
    val eaten by animateIntAsState(eatenCg / CENTIGRAMS_PER_GRAM, tween(NUMBER_MS, easing = EaseOutStrong))

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(palette.tile)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(if (lowerBound) "≥$eaten" else "$eaten", style = type.number, color = KcalTheme.colors.text)
                if (goalGrams != null) {
                    Text("/$goalGrams", style = type.number.copy(fontWeight = FontWeight.W500), color = palette.text)
                }
            }
            home()
        }
        if (goalGrams != null && goalGrams > 0) {
            val fraction by animateFloatAsState(
                eatenCg.toFloat() / (goalGrams * CENTIGRAMS_PER_GRAM),
                tween(NUMBER_MS, easing = EaseOutStrong),
            )
            MiniBar(fraction = fraction, fill = palette.fill)
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Поверх неживого дневника — Масло и что сделать. Дымка забирает клики: писать
 * некуда, пока телефон не ответит, а страница подключится сама.
 */
@Composable
private fun OfflineOverlay(line: PageLine) {
    val colors = KcalTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = OFFLINE_SCRIM))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.enter().widthIn(max = 360.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MascotFigure(line.mascot, MascotPose.WAVE, Modifier.size(120.dp, 100.dp))
            Text(
                line.text,
                style = KcalTheme.type.body,
                color = colors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(KcalShapes.bubble))
                    .background(colors.surface)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }
}
