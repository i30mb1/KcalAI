package n7.kcalai.web

import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.browser.window
import kotlinx.datetime.LocalDate
import n7.kcalai.model.MealType
import n7.kcalai.model.dayLabel
import n7.kcalai.model.formatCentigrams
import n7.kcalai.model.formatFiber
import n7.kcalai.model.formatGrams
import n7.kcalai.model.title
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.Macro
import n7.kcalai.ui.MacroDot
import n7.kcalai.ui.colors
import n7.kcalai.ui.letter
import n7.kcalai.ui.mascot.MascotFigure
import n7.kcalai.ui.mascot.MascotPose
import n7.kcalai.ui.mascot.macro
import n7.kcalai.webapi.PageLine
import n7.kcalai.webapi.WebSaid
import n7.kcalai.webapi.PageState
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WEB_GRAMS_MAX
import n7.kcalai.webapi.WebEntry
import n7.kcalai.webapi.byMeal
import n7.kcalai.webapi.fiber
import n7.kcalai.webapi.line
import n7.kcalai.webapi.peersLine
import n7.kcalai.webapi.totals

/** Ширина пузырька приёма: как в мессенджере, реплика не тянется от края до края. */
private const val BUBBLE_WIDTH = 0.82f
private const val SEPARATOR_DP = 10

/** Плашки под подписями: почти цвет страницы — над миром прогулки они читаются, а дома их не видно. */
internal const val PLATE_ALPHA = 0.9f

/** Строка ленты: разделитель дня, приём или реплика персонажа. */
private sealed interface FeedRow {
    val key: String

    data class Separator(val label: String) : FeedRow {
        override val key get() = "separator"
    }

    data class Meal(val meal: MealType, val entries: List<WebEntry>) : FeedRow {
        override val key get() = "meal-$meal"
    }

    data class Said(val line: PageLine, val links: List<Pair<String, String>> = emptyList()) : FeedRow {
        override val key get() = "said-${line.mascot}"
    }

    /** Итог прогулки: его сказал персонаж в ленте телефона, и он там остаётся — как переписка. */
    data class Walked(val said: WebSaid) : FeedRow {
        override val key get() = "walked-${said.minute}-${said.text.hashCode()}"
    }
}

/**
 * Лента дня — переписка, прижатая к строке ввода: мало записей — они внизу, у поля,
 * как в мессенджере; новая запись — лента доезжает до неё.
 */
@Composable
fun Feed(diary: PageState.Diary, link: PhoneLink, modifier: Modifier) {
    val day = diary.day
    var editing by remember { mutableStateOf<Long?>(null) }
    val rows = buildList {
        add(FeedRow.Separator(dayLabel(LocalDate.fromEpochDays(day.date), LocalDate.fromEpochDays(day.today))))
        day.byMeal().forEach { (meal, entries) -> add(FeedRow.Meal(meal, entries)) }
        day.said.sortedBy { it.minute }.forEach { add(FeedRow.Walked(it)) }
        // Персонажи говорят последними, у поля: это ответ на то, что сейчас на экране.
        diary.line()?.let { add(FeedRow.Said(it)) }
        diary.peersLine()?.let { add(FeedRow.Said(it, day.peers.map { peer -> peer.name to peer.url })) }
    }
    val list = rememberLazyListState()
    LaunchedEffect(rows.size, day.entries.size) { list.animateScrollToItem(rows.lastIndex.coerceAtLeast(0)) }

    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
    ) {
        items(rows, key = { it.key }) { row ->
            // Удалённое пропадает сразу, как в ленте приложения: затухающий пузырёк повисал
            // призраком поверх новых строк, когда лента становилась короче экрана.
            Box(Modifier.animateItem(fadeOutSpec = null)) {
                when (row) {
                    // Подпись дня — на своей плашке цвета страницы: читается и над миром прогулки без стекла.
                    is FeedRow.Separator -> CapsLabel(
                        row.label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentWidth(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(SEPARATOR_DP.dp))
                            .background(KcalTheme.colors.bg.copy(alpha = PLATE_ALPHA))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    is FeedRow.Meal -> MealBubble(
                        meal = row.meal,
                        entries = row.entries,
                        editing = editing,
                        onEdit = { id -> editing = id },
                        onGrams = { id, grams -> link.send(ToPhone.SetGrams(id, grams)) },
                        onDelete = { id -> link.send(ToPhone.Delete(id)) },
                    )
                    is FeedRow.Said -> IncomingLine(row.line, links = row.links)
                    is FeedRow.Walked -> IncomingLine(
                        PageLine(row.said.mascot, row.said.text),
                        onHide = { link.send(ToPhone.Hide(row.said.id)) },
                    )
                }
            }
        }
    }
}

/**
 * Приём — тёмным пузырьком справа, как реплика человека в приложении: что за приём
 * и сколько в нём калорий, из чего он, макросы с клетчаткой и время.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealBubble(
    meal: MealType,
    entries: List<WebEntry>,
    editing: Long?,
    onEdit: (Long?) -> Unit,
    onGrams: (Long, Int) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val colors = KcalTheme.colors
    val type = KcalTheme.type
    val totals = entries.totals()

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(
            modifier = Modifier
                .fillMaxWidth(BUBBLE_WIDTH)
                .wrapContentWidth(Alignment.End)
                .clip(
                    RoundedCornerShape(
                        topStart = KcalShapes.bubble,
                        topEnd = KcalShapes.bubble,
                        bottomEnd = KcalShapes.bubbleTail,
                        bottomStart = KcalShapes.bubble,
                    )
                )
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
                            EntryChip(entry, onClick = { onEdit(entry.id) }, onDelete = { onDelete(entry.id) })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MacroValue(Macro.PROTEIN, totals.protCg)
                MacroValue(Macro.FAT, totals.fatCg)
                MacroValue(Macro.CARB, totals.carbCg)
                // Клетчатка — следом за углеводами, цветом хохолка Зёрнышка. Неизвестная у всех — не показывается.
                formatFiber(entries.fiber())?.let { fiber ->
                    Text("Кл $fiber", style = type.macro.copy(fontWeight = FontWeight.W700), color = colors.fiber.onBubble)
                }
                entries.firstOrNull()?.time?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = type.time, color = colors.onBubble2)
                }
            }
        }
    }
}

/**
 * Продукт в пузырьке: точка хозяина макроса, название, вес и крестик. Тап — правка граммов
 * на месте, крестик — удалить сразу: записал не то — убрал одним нажатием.
 */
@Composable
private fun EntryChip(entry: WebEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    val colors = KcalTheme.colors
    Row(
        modifier = Modifier
            .enter()
            .press(RoundedCornerShape(KcalShapes.chip), colors.onBubble.copy(alpha = HOVER_ALPHA), onClick)
            .background(colors.chipOnBubble)
            .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacroDot(entry.owner.macro.colors().onBubble)
        Text(
            entry.name,
            style = KcalTheme.type.chip,
            color = colors.onBubble,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 7.dp).widthIn(max = 280.dp),
        )
        Text(
            "· ${formatGrams(entry.grams)}",
            style = KcalTheme.type.time,
            color = colors.onBubble2,
            modifier = Modifier.padding(start = 6.dp),
        )
        Text(
            "×",
            style = KcalTheme.type.bubbleTitle,
            color = colors.onBubble2,
            modifier = Modifier
                .padding(start = 4.dp)
                .press(RoundedCornerShape(10.dp), colors.error.copy(alpha = 0.18f), onDelete)
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
            modifier = Modifier.widthIn(max = 220.dp),
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
 * Реплика персонажа — слева, с ним самим у хвостика пузырька, как в ленте приложения.
 *
 * @param action главный ответ — тёмный чипс; [links] — ссылки на чужие страницы
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IncomingLine(
    line: PageLine?,
    action: Pair<String, () -> Unit>? = null,
    links: List<Pair<String, String>> = emptyList(),
    /** Корзинка: убрать реплику из переписки — на странице и на телефоне. */
    onHide: (() -> Unit)? = null,
) {
    val colors = KcalTheme.colors
    if (line == null) return
    key(line) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            MascotFigure(line.mascot, MascotPose.WAVE, Modifier.size(54.dp, 45.dp))
            Spacer(Modifier.width(6.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(BUBBLE_WIDTH)
                    .wrapContentWidth(Alignment.Start)
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
                Row(verticalAlignment = Alignment.Top) {
                    Text(line.text, style = KcalTheme.type.body, color = colors.text, modifier = Modifier.weight(1f, fill = false))
                    if (onHide != null) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Удалить",
                            tint = colors.text3,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .press(RoundedCornerShape(8.dp), colors.text.copy(alpha = HOVER_ALPHA), onHide)
                                .padding(2.dp)
                                .size(16.dp),
                        )
                    }
                }
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

/** Ширина цифры моноширинного шрифта чисел и запас под курсор — для поля граммов. */
private const val DIGIT_DP = 8.5f
private const val CARET_DP = 4f
