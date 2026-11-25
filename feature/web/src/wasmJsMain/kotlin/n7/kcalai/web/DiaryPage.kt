package n7.kcalai.web

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
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
import n7.kcalai.webapi.WebTile
import n7.kcalai.webapi.tileSpeech
import n7.kcalai.webapi.arrivals
import n7.kcalai.webapi.line
import n7.kcalai.webapi.offlineLine

/**
 * Ширина колонки. Дневник — переписка, и строки в ней не тянутся на весь монитор,
 * но и не жмутся: шапке с четырьмя плитками и неделей нужен воздух. Стекло над миром
 * прогулки — той же ширины, см. index.html.
 */
private val COLUMN_MAX = 760.dp

/** Шагомер в поле справа: не уже этого — иначе окошком в ленте; шире — без пользы. */
private val DOCK_PANEL = 280.dp
private val DOCK_PANEL_MAX = 360.dp
private val DOCK_MARGIN = 24.dp

/** Число калорий доезжает до нового — но быстро: это ответ на действие, а не заставка. */
internal const val NUMBER_MS = 280

/** Подсветка под курсором — едва заметная: она говорит «нажимается», а не кричит. */
internal const val HOVER_ALPHA = 0.08f

internal const val CENTIGRAMS_PER_GRAM = 100

/** Смена дня: лента сдвигается на шестую ширины — заметно, куда листаешь, но без карусели. */
private const val DAY_SLIDE = 6
private const val DAY_IN_MS = 260
private const val DAY_OUT_MS = 180

/** Уже этого — экран телефона: плитки встают в две строки. */
private val NARROW = 520.dp

/** Сколько облачко хозяина плитки висит над ней. */
private const val SPEECH_MS = 4_000L

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
    // Прогулка: под дневником оживает бумажный мир, по нему идёт Зёрнышко (см. Walk.kt). Пока идём,
    // фон страницы уходит — колонка становится матовым стеклом над миром (его рисует index.html).
    // Живые числа — с дорожки, подключённой к телефону (или демо по ?demo).
    val live = rememberDemoWalk() ?: diary?.day?.live
    var worldAlways by remember { mutableStateOf(walkAlways()) }
    // Мир виден на ходу — или всегда, если так выбрано в настройках: тогда герой стоит там, докуда дошёл за день.
    val worldShown = rememberWalking(colors.bg, live?.speedKmh ?: 0.0, diary?.day?.steps ?: STEPS_UNKNOWN, diary?.day?.stepsGoal ?: 0, worldAlways)
    val walking = (live?.speedKmh ?: 0.0) > MOVING_KMH
    val pageBg by animateColorAsState(if (worldShown) colors.bg.copy(alpha = 0f) else colors.bg, tween(WALK_FADE_MS))

    BoxWithConstraints(Modifier.fillMaxSize().background(pageBg), contentAlignment = Alignment.TopCenter) {
        // Поле справа от колонки: хватает места — шагомер стоит там отдельной панелью, а не поверх ленты.
        val gutter = (maxWidth - COLUMN_MAX) / 2
        val docked = gutter >= DOCK_PANEL + DOCK_MARGIN * 2
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
                // Смена дня — лента уезжает в сторону листания и растворяется, а не подменяется
                // разом. Обновления того же дня сменой не считаются: ключ — дата.
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = diary,
                        contentKey = { it.day.date },
                        transitionSpec = {
                            val forward = targetState.day.date > initialState.day.date
                            val enterFrom: (Int) -> Int = { width -> if (forward) width / DAY_SLIDE else -width / DAY_SLIDE }
                            val exitTo: (Int) -> Int = { width -> if (forward) -width / DAY_SLIDE else width / DAY_SLIDE }
                            (slideInHorizontally(tween(DAY_IN_MS, easing = EaseOutStrong), enterFrom) + fadeIn(tween(DAY_IN_MS))) togetherWith
                                (slideOutHorizontally(tween(DAY_OUT_MS, easing = EaseOutStrong), exitTo) + fadeOut(tween(DAY_OUT_MS)))
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "day",
                    ) { shown -> Feed(shown, link, Modifier.fillMaxSize()) }
                    // Шаги дня — поверх ленты, в углу: окошко ничего не сдвигает (когда поля справа нет).
                    if (!docked && diary.day.date == diary.day.today) {
                        WalkPanel(diary.day, live, walking, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 16.dp))
                    }
                }
                Composer(diary, link)
            }
        }
        if (docked && diary != null && diary.day.date == diary.day.today) {
            WalkPanel(
                diary.day,
                live,
                walking,
                Modifier.align(Alignment.TopEnd).padding(top = DOCK_MARGIN, end = DOCK_MARGIN),
                docked = true,
                width = minOf(DOCK_PANEL_MAX, gutter - DOCK_MARGIN * 2),
            )
        }
        if (diary != null && offline) {
            OfflineOverlay(diary.offlineLine())
        } else if (settings && diary != null) {
            SettingsPanel(
                diary.day,
                link,
                worldAlways = worldAlways,
                onWorldAlways = { on ->
                    worldAlways = on
                    setWalkAlways(on)
                },
                onClose = { settings = false },
            )
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
            // Своя плашка цвета страницы: над миром прогулки шапка читается и без стекла колонки.
            .background(colors.bg.copy(alpha = PLATE_ALPHA), RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
            .drawBehind { drawLine(colors.line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f) }
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Чей дневник и какой день. Прошлый день — тапом обратно в сегодня, как в шапке приложения.
            Crossfade(targetState = label, animationSpec = tween(DAY_IN_MS), label = "date") { shown ->
                CapsLabel(
                    text = listOfNotNull(day.owner.takeIf { it.isNotBlank() }, if (past) "← $shown" else shown).joinToString(" · "),
                    color = if (past) colors.text else colors.text3,
                    modifier = Modifier
                        .then(if (past) Modifier.press(RoundedCornerShape(8.dp), colors.text.copy(alpha = HOVER_ALPHA)) { onOpen(day.today) } else Modifier)
                        .padding(vertical = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            // Прошлый день — кнопка обратно, на виду: стрелку в подписи находят не сразу.
            if (past) {
                PageChip("Сегодня", primary = true, onClick = { onOpen(day.today) })
                Spacer(Modifier.width(4.dp))
            }
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
        MacroTiles(totals, day.goalMacros, day.fiber(), day.fiberGoal, puppets, past)
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
    past: Boolean,
) {
    val scope = rememberCoroutineScope()
    // Кто сейчас говорит и сколько раз подряд тапали по каждой плитке: фразы идут по кругу.
    var speaking by remember { mutableStateOf<Pair<WebTile, PageLine>?>(null) }
    val taps = remember { mutableMapOf<WebTile, Int>() }
    LaunchedEffect(speaking) {
        if (speaking == null) return@LaunchedEffect
        delay(SPEECH_MS)
        speaking = null
    }

    val tiles = listOf(
        Triple(WebTile.PROTEIN, totals.protCg, goal?.prot),
        Triple(WebTile.FAT, totals.fatCg, goal?.fat),
        Triple(WebTile.CARB, totals.carbCg, goal?.carb),
        Triple(WebTile.FIBER, fiber.cg, fiberGoal),
    )
    // На узком экране — браузер телефона — четыре плитки в ряд не влезают: две строки по две.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rows = if (maxWidth < NARROW) tiles.chunked(2) else listOf(tiles)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    rows.forEach { row ->
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { (tile, eatenCg, goalGrams) ->
            val macro = tile.macro
            val puppet = macro?.let { puppets.getValue(it.mascot) }
            Tile(
                palette = macro?.colors() ?: KcalTheme.colors.fiber,
                eatenCg = eatenCg,
                goalGrams = goalGrams,
                // Неизвестная у части продуктов клетчатка — нижняя граница «≥», а не выдуманный ноль.
                lowerBound = tile == WebTile.FIBER && !fiber.complete && fiber.cg > 0,
                speech = speaking?.takeIf { it.first == tile }?.second,
                onTap = {
                    val tap = taps[tile] ?: 0
                    taps[tile] = tap + 1
                    speaking = tile to tileSpeech(tile, eatenCg, goalGrams, fiber, tap, past)
                    puppet?.let { scope.launch { it.play(MascotAct.SHAKE) } }
                },
                modifier = Modifier.weight(1f),
            ) {
                if (puppet != null) {
                    MascotFigure(puppet = puppet, pose = MascotPose.HOME, modifier = Modifier.size(width = 42.dp, height = 35.dp))
                } else {
                    // Клетчатка — росток Зёрнышка: она его тема.
                    Box(Modifier.size(width = 42.dp, height = 35.dp), contentAlignment = Alignment.Center) {
                        SproutIcon(KcalTheme.colors.fiber.text, size = 22.dp)
                    }
                }
            }
        }
    }
    }
        }
    }
}

/**
 * Плитка: число, домик хозяина и полоса до цели. Тап — хозяин отвечает облачком над плиткой.
 * Облачко — поверх страницы, а не в ней: появление реплики ничего не сдвигает.
 */
@Composable
private fun Tile(
    palette: MacroColors,
    eatenCg: Int,
    goalGrams: Int?,
    lowerBound: Boolean,
    speech: PageLine?,
    onTap: () -> Unit,
    modifier: Modifier,
    home: @Composable () -> Unit,
) {
    val type = KcalTheme.type
    val eaten by animateIntAsState(eatenCg / CENTIGRAMS_PER_GRAM, tween(NUMBER_MS, easing = EaseOutStrong))

    // Облачко — в обёртке, а не в колонке: колонка добавила бы отступ и для невидимого
    // окна, и плитка подрастала бы на тап, сдвигая неделю под ней.
    Box(modifier) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .press(RoundedCornerShape(KcalShapes.tile), KcalTheme.colors.text.copy(alpha = HOVER_ALPHA), onTap)
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
    // Внутри обёртки плитки: облачко встаёт над той плиткой, по которой тапнули.
    if (speech != null) {
        key(speech) { SpeechPopup(speech, palette) }
    }
    }
}

/**
 * Облачко хозяина плитки — цвета плитки, поверх самого верха страницы, над своей плиткой:
 * ничего не сдвигает и не закрывает итог дня, на который смотрят.
 */
@Composable
private fun SpeechPopup(line: PageLine, palette: MacroColors) {
    Popup(popupPositionProvider = AtTopOverAnchor) {
        Text(
            line.text,
            style = KcalTheme.type.body,
            color = KcalTheme.colors.text,
            modifier = Modifier
                .padding(top = 8.dp, start = 8.dp, end = 8.dp)
                .widthIn(max = 320.dp)
                .enter(origin = TransformOrigin(0.5f, 0f))
                .clip(RoundedCornerShape(KcalShapes.bubble))
                .background(palette.tile)
                .border(1.dp, palette.fill.copy(alpha = 0.5f), RoundedCornerShape(KcalShapes.bubble))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** У верхнего края окна, по центру над якорем, не за краем. */
private object AtTopOverAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), 0)
    }
}

/** Макрос плитки; у клетчатки своего макроса нет — она росток Зёрнышка. */
private val WebTile.macro: Macro?
    get() = when (this) {
        WebTile.PROTEIN -> Macro.PROTEIN
        WebTile.FAT -> Macro.FAT
        WebTile.CARB -> Macro.CARB
        WebTile.FIBER -> null
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
