package n7.kcalai.web

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.formatCentigrams
import n7.kcalai.model.formatGrams
import n7.kcalai.model.title
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.Macro
import n7.kcalai.ui.MacroDot
import n7.kcalai.ui.MiniBar
import n7.kcalai.ui.colors
import n7.kcalai.ui.letter
import n7.kcalai.ui.mascot.MascotAct
import n7.kcalai.ui.mascot.MascotFigure
import n7.kcalai.ui.mascot.MascotPose
import n7.kcalai.ui.mascot.MascotPuppet
import n7.kcalai.ui.mascot.macro
import n7.kcalai.ui.mascot.mascot
import n7.kcalai.ui.mascot.rememberMascotPuppet
import n7.kcalai.webapi.PageLine
import n7.kcalai.webapi.PageState
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WEB_GRAMS_MAX
import n7.kcalai.webapi.WebDay
import n7.kcalai.webapi.WebEntry
import n7.kcalai.webapi.WebMacros
import n7.kcalai.webapi.WebSuggestion
import n7.kcalai.webapi.arrivals
import n7.kcalai.webapi.byMeal
import n7.kcalai.webapi.line
import n7.kcalai.webapi.peersLine
import n7.kcalai.webapi.totals

/** Ширина колонки: на широком мониторе дневник не растягивается в строку на весь экран. */
private val COLUMN_MAX = 600.dp

/** Пауза в наборе перед поиском — как у строки ввода в приложении. */
private const val SEARCH_DEBOUNCE_MS = 200L

/** Число калорий доезжает до нового — но быстро: это ответ на действие, а не заставка. */
private const val NUMBER_MS = 280

/** Подсветка под курсором — едва заметная: она говорит «нажимается», а не кричит. */
private const val HOVER_ALPHA = 0.08f

private const val CENTIGRAMS_PER_GRAM = 100

/** Ширина цифры моноширинного шрифта чисел и запас под курсор — для поля граммов. */
private const val DIGIT_DP = 8.5f
private const val CARET_DP = 4f

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
        document.title = diary?.day?.owner?.let { "$it · дневник" } ?: "Дневник"
    }

    Box(Modifier.fillMaxSize().background(colors.bg), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = COLUMN_MAX).fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (diary == null) {
                // До пуска на странице только разговор: код, отказ или пропавшая связь.
                Line(state.line(), action = if (state == PageState.Denied) ("Попросить" to link::askAgain) else null)
            } else {
                Header(diary.day, puppets, onLeave = link::leave)
                Meals(diary.day, link, Modifier.weight(1f))
                // Реплики — у строки ввода, как в переписке: там, куда человек и смотрит.
                diary.line()?.let { Line(it) }
                diary.peersLine()?.let { peers ->
                    Line(peers, links = diary.day.peers.map { it.name to it.url })
                }
                Composer(diary, link)
            }
        }
    }
}

/**
 * Шапка — как у приложения: чей дневник, крупный итог дня с полосой до цели и три
 * плитки макросов с хозяевами. Две вкладки двух телефонов различаются с первого
 * взгляда: имя хозяина стоит сверху.
 */
@Composable
private fun Header(day: WebDay, puppets: Map<Mascot, MascotPuppet>, onLeave: () -> Unit) {
    val colors = KcalTheme.colors
    val type = KcalTheme.type
    val totals = day.totals()
    val kcal by animateIntAsState(totals.kcal, tween(NUMBER_MS, easing = EaseOutStrong))
    val goal = day.goalKcal
    val over = goal != null && totals.kcal > goal

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CapsLabel("${day.owner} · сегодня", modifier = Modifier.weight(1f))
            // Действие не назвать данными — поэтому словом, и тихо: главное на странице не оно.
            Text(
                "Выйти",
                style = type.chip,
                color = colors.text3,
                modifier = Modifier
                    .press(RoundedCornerShape(8.dp), colors.text.copy(alpha = HOVER_ALPHA), onLeave)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
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
        MacroTiles(totals, day.goalMacros, puppets)
    }
}

@Composable
private fun MacroTiles(totals: NutrimentTotals, goal: WebMacros?, puppets: Map<Mascot, MascotPuppet>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple(Macro.PROTEIN, totals.protCg, goal?.prot),
            Triple(Macro.FAT, totals.fatCg, goal?.fat),
            Triple(Macro.CARB, totals.carbCg, goal?.carb),
        ).forEach { (macro, eatenCg, goalGrams) ->
            MacroTile(macro, eatenCg, goalGrams, puppets.getValue(macro.mascot), Modifier.weight(1f))
        }
    }
}

/** Плитка макроса: число, домик персонажа и полоса. Тап по персонажу — он трясётся, как в приложении. */
@Composable
private fun MacroTile(macro: Macro, eatenCg: Int, goalGrams: Int?, puppet: MascotPuppet, modifier: Modifier) {
    val type = KcalTheme.type
    val palette = macro.colors()
    val eaten by animateIntAsState(eatenCg / CENTIGRAMS_PER_GRAM, tween(NUMBER_MS, easing = EaseOutStrong))
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(palette.tile)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text("$eaten", style = type.number, color = KcalTheme.colors.text)
                if (goalGrams != null) {
                    Text("/$goalGrams", style = type.number.copy(fontWeight = FontWeight.W500), color = palette.text)
                }
            }
            MascotFigure(
                puppet = puppet,
                pose = MascotPose.HOME,
                modifier = Modifier.size(width = 42.dp, height = 35.dp).pointerInput(puppet) {
                    detectTapGestures { scope.launch { puppet.play(MascotAct.SHAKE) } }
                },
            )
        }
        // Место под полосу есть всегда: появление цели не сдвигает плитки.
        if (goalGrams != null) {
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
 * Реплика персонажа — пузырёк с ним рядом, как в ленте приложения. До пуска высота
 * зарезервирована, даже когда сказать нечего: появление реплики не толкает страницу.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Line(
    line: PageLine?,
    action: Pair<String, () -> Unit>? = null,
    links: List<Pair<String, String>> = emptyList(),
) {
    val colors = KcalTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.Bottom) {
        if (line == null) return@Row
        key(line) {
            MascotFigure(line.mascot, MascotPose.WAVE, Modifier.size(54.dp, 45.dp))
            Spacer(Modifier.width(6.dp))
            Column(
                Modifier
                    .enter()
                    .clip(
                        RoundedCornerShape(
                            topStart = KcalShapes.bubble,
                            topEnd = KcalShapes.bubble,
                            bottomEnd = KcalShapes.bubble,
                            bottomStart = KcalShapes.bubbleTail,
                        )
                    )
                    .background(colors.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(line.text, style = KcalTheme.type.body, color = colors.text)
                if (action != null || links.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        action?.let { (text, onClick) -> PageChip(text, primary = true, onClick = onClick) }
                        links.forEach { (name, url) -> PageChip(name, primary = false, onClick = { window.open(url, "_blank") }) }
                    }
                }
            }
        }
    }
}

/** Приёмы дня — тёмными пузырьками, как записи в ленте приложения. */
@Composable
private fun Meals(day: WebDay, link: PhoneLink, modifier: Modifier) {
    var editing by remember { mutableStateOf<Long?>(null) }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(day.byMeal(), key = { (meal, _) -> meal.name }) { (meal, entries) ->
            MealCard(
                meal = meal,
                entries = entries,
                editing = editing,
                onEdit = { id -> editing = id },
                onGrams = { id, grams -> link.send(ToPhone.SetGrams(id, grams)) },
                onDelete = { id -> link.send(ToPhone.Delete(id)) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealCard(
    meal: MealType,
    entries: List<WebEntry>,
    editing: Long?,
    onEdit: (Long?) -> Unit,
    onGrams: (Long, Int) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier,
) {
    val colors = KcalTheme.colors
    val type = KcalTheme.type
    val totals = entries.totals()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KcalShapes.bubble))
            .background(colors.bubble)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // Перекус приглушён, как в приложении: это не полноценный приём пищи.
            Text(meal.title, style = type.bubbleTitle, color = if (meal == MealType.SNACK) colors.onBubble2 else colors.onBubble)
            Spacer(Modifier.width(9.dp))
            Text("${totals.kcal} ккал", style = type.numberSmall, color = colors.onBubble2)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            entries.forEach { entry ->
                key(entry.id) {
                    if (editing == entry.id) {
                        EntryEditor(
                            entry = entry,
                            onDone = { grams ->
                                grams?.let { onGrams(entry.id, it) }
                                onEdit(null)
                            },
                            onDelete = {
                                onDelete(entry.id)
                                onEdit(null)
                            },
                        )
                    } else {
                        EntryChip(entry, onClick = { onEdit(entry.id) })
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MacroValue(Macro.PROTEIN, totals.protCg)
            MacroValue(Macro.FAT, totals.fatCg)
            MacroValue(Macro.CARB, totals.carbCg)
        }
    }
}

/** Продукт в пузырьке: точка хозяина макроса, название, вес. Тап — правка на месте. */
@Composable
private fun EntryChip(entry: WebEntry, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    Row(
        modifier = Modifier
            .enter()
            .press(RoundedCornerShape(KcalShapes.chip), colors.onBubble.copy(alpha = HOVER_ALPHA), onClick)
            .background(colors.chipOnBubble)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacroDot(entry.owner.macro.colors().onBubble)
        Text(
            entry.name,
            style = KcalTheme.type.chip,
            color = colors.onBubble,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 7.dp).widthIn(max = 260.dp),
        )
        Text(
            "· ${formatGrams(entry.grams)}",
            style = KcalTheme.type.time,
            color = colors.onBubble2,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * Правка записи на месте: граммы — сразу с фокусом и выделенным числом, Enter — сохранить,
 * Esc или клик мимо — оставить как было, крестик — удалить.
 *
 * @param onDone новые граммы; `null` — правку отменили
 */
@Composable
private fun EntryEditor(entry: WebEntry, onDone: (Int?) -> Unit, onDelete: () -> Unit) {
    val colors = KcalTheme.colors
    val type = KcalTheme.type
    var typed by remember {
        val grams = entry.grams.toString()
        mutableStateOf(TextFieldValue(grams, selection = TextRange(0, grams.length)))
    }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(KcalShapes.chip))
            .background(colors.onBubble)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.name,
            style = type.chip,
            color = colors.bubble,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = typed,
            onValueChange = { value -> typed = value.copy(text = value.text.filter(Char::isDigit).take(5)) },
            singleLine = true,
            textStyle = type.numberSmall.copy(color = colors.bubble),
            cursorBrush = SolidColor(colors.bubble),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            // Ширина — по числу: моноширинные цифры, и «г» стоит вплотную, а не через пустоту.
            modifier = Modifier
                .width((typed.text.length.coerceAtLeast(1) * DIGIT_DP + CARET_DP).dp)
                .focusRequester(focus)
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) onDone(null)
                    focused = state.isFocused
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onDone(typed.text.toIntOrNull()?.takeIf { it in 1..WEB_GRAMS_MAX })
                            true
                        }
                        Key.Escape -> {
                            onDone(null)
                            true
                        }
                        else -> false
                    }
                },
        )
        Text("г", style = type.numberSmall, color = colors.bubble.copy(alpha = 0.6f))
        Spacer(Modifier.width(4.dp))
        Text(
            "×",
            style = type.title,
            color = colors.error,
            modifier = Modifier
                .press(RoundedCornerShape(10.dp), colors.error.copy(alpha = HOVER_ALPHA), onDelete)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MacroValue(macro: Macro, centigrams: Int) {
    Text("${macro.letter} ${formatCentigrams(centigrams)}", style = KcalTheme.type.macro, color = macro.colors().onBubble)
}

/**
 * Строка ввода: тот же поиск, что в приложении, подсказки — чипсами над ней.
 * ↑ и ↓ выбирают подсказку, Enter записывает выбранную, Esc очищает строку.
 * Клавиатурные действия — без анимации: их повторяют десятки раз подряд.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(diary: PageState.Diary, link: PhoneLink) {
    val colors = KcalTheme.colors
    var input by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val found = diary.suggestionsFor(input)
    var selected by remember(found) { mutableStateOf(0) }

    LaunchedEffect(input) {
        if (input.isBlank()) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        link.send(ToPhone.Search(input.trim()))
    }

    fun add(index: Int) {
        val item = found.getOrNull(index) ?: return
        link.send(ToPhone.Add(query = input.trim(), index = index, grams = item.grams))
        input = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Место под подсказки держится всегда: появление чипсов не двигает строку ввода.
        FlowRow(
            Modifier.fillMaxWidth().heightIn(min = 38.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            found.forEachIndexed { index, item ->
                SuggestionChip(item, chosen = index == selected, onClick = { add(index) })
            }
            if (diary.nothingFor(input)) {
                Text("Не нашлось", style = KcalTheme.type.chip, color = colors.text3, modifier = Modifier.padding(vertical = 9.dp))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(KcalShapes.input))
                .background(colors.surface)
                .border(1.dp, if (focused) colors.text.copy(alpha = 0.25f) else colors.line, RoundedCornerShape(KcalShapes.input))
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            if (input.isEmpty()) {
                Text("Написать, что съели…", style = KcalTheme.type.input, color = colors.text3)
            }
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = KcalTheme.type.input.copy(color = colors.text),
                cursorBrush = SolidColor(colors.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                add(selected)
                                true
                            }
                            Key.DirectionDown -> {
                                if (found.isNotEmpty()) selected = (selected + 1) % found.size
                                found.isNotEmpty()
                            }
                            Key.DirectionUp -> {
                                if (found.isNotEmpty()) selected = (selected - 1 + found.size) % found.size
                                found.isNotEmpty()
                            }
                            Key.Escape -> {
                                input = ""
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
    }
}

/** Подсказка: точка хозяина макроса, название, порция и калории. Выбранная — тёмная, с ↵. */
@Composable
private fun SuggestionChip(item: WebSuggestion, chosen: Boolean, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    val ink = if (chosen) colors.onBubble else colors.text
    val palette = item.mascot.macro.colors()
    Row(
        modifier = Modifier
            .press(RoundedCornerShape(KcalShapes.chip), ink.copy(alpha = HOVER_ALPHA), onClick)
            .background(if (chosen) colors.bubble else colors.chip)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacroDot(if (chosen) palette.onBubble else palette.fill)
        Text(
            item.name,
            style = KcalTheme.type.chip,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 7.dp).widthIn(max = 260.dp),
        )
        Text(
            "· ${formatGrams(item.grams)} · ${item.kcal}",
            style = KcalTheme.type.time,
            color = if (chosen) colors.onBubble2 else colors.text3,
            modifier = Modifier.padding(start = 6.dp),
        )
        if (chosen) {
            Text("↵", style = KcalTheme.type.time, color = colors.onBubble2, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Чипс-действие в реплике: главный — тёмный, как реплика человека, остальные — светлые. */
@Composable
private fun PageChip(text: String, primary: Boolean, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    val ink = if (primary) colors.onBubble else colors.text
    Text(
        text,
        style = KcalTheme.type.chip,
        color = ink,
        modifier = Modifier
            .press(RoundedCornerShape(KcalShapes.chip), ink.copy(alpha = HOVER_ALPHA), onClick)
            .background(if (primary) colors.bubble else colors.chip)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
