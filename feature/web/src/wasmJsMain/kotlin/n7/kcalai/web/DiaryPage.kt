package n7.kcalai.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import n7.kcalai.model.title
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.ChipNumbers
import n7.kcalai.ui.KcalChip
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.mascot.MascotFigure
import n7.kcalai.ui.mascot.MascotPose
import n7.kcalai.webapi.PageLine
import n7.kcalai.webapi.PageState
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WEB_GRAMS_MAX
import n7.kcalai.webapi.WebEntry
import n7.kcalai.webapi.byMeal
import n7.kcalai.webapi.line
import n7.kcalai.webapi.peersLine

/** Ширина колонки: на широком мониторе дневник не растягивается в строку на весь экран. */
private val COLUMN_MAX = 560.dp

/** Пауза в наборе перед поиском — как у строки ввода в приложении. */
private const val SEARCH_DEBOUNCE_MS = 200L

@Composable
fun DiaryPage(link: PhoneLink) {
    val state by link.state.collectAsState()
    val colors = KcalTheme.colors

    Box(Modifier.fillMaxSize().background(colors.bg), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = COLUMN_MAX).fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val diary = state as? PageState.Diary
            Header(diary, onLeave = link::leave)
            // Место под реплику держится всегда: смена состояния не сдвигает дневник.
            Line(state.line(), action = if (state == PageState.Denied) ("Попросить" to link::askAgain) else null)
            if (diary != null) {
                Entries(diary, link, Modifier.weight(1f))
                Composer(diary, link)
                diary.peersLine()?.let { peers ->
                    Line(peers, links = diary.day.peers.map { it.name to it.url })
                }
            }
        }
    }
}

/** Итог дня против цели — главное число страницы, как плитка калорий в приложении. */
@Composable
private fun Header(diary: PageState.Diary?, onLeave: () -> Unit) {
    val colors = KcalTheme.colors
    LaunchedEffect(diary?.day?.owner) {
        document.title = diary?.day?.owner?.let { "Дневник · $it" } ?: "Дневник"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.Bottom) {
        if (diary != null) {
            Text(diary.day.kcal.toString(), style = KcalTheme.type.hero, color = colors.text)
            Text(
                diary.day.goalKcal?.let { " / $it ккал" } ?: " ккал",
                style = KcalTheme.type.number,
                color = colors.text3,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            // Действие не назвать данными — поэтому словом, и тихо: главное на странице не оно.
            Text(
                "Выйти",
                style = KcalTheme.type.chip,
                color = colors.text3,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLeave).padding(8.dp),
            )
        }
    }
}

/**
 * Реплика персонажа. Высота зарезервирована, даже когда сказать нечего: появление
 * и смена реплики не толкают записи вниз.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Line(
    line: PageLine?,
    action: Pair<String, () -> Unit>? = null,
    links: List<Pair<String, String>> = emptyList(),
) {
    val colors = KcalTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (line == null) return@Row
        MascotFigure(line.mascot, MascotPose.HOME, Modifier.size(48.dp, 40.dp))
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(line.text, style = KcalTheme.type.body, color = colors.text)
            if (action != null || links.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    action?.let { (text, onClick) ->
                        KcalChip(onClick = onClick, background = colors.bubble) {
                            Text(text, style = KcalTheme.type.chip, color = colors.onBubble)
                        }
                    }
                    links.forEach { (name, url) ->
                        KcalChip(onClick = { window.open(url, "_blank") }, background = colors.chip) {
                            Text(name, style = KcalTheme.type.chip, color = colors.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Entries(diary: PageState.Diary, link: PhoneLink, modifier: Modifier) {
    val colors = KcalTheme.colors
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        diary.day.byMeal().forEach { (meal, entries) ->
            item(key = "meal-$meal") {
                CapsLabel(meal.title, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
            }
            items(entries, key = { it.id }) { entry ->
                EntryRow(
                    entry = entry,
                    onGrams = { grams -> link.send(ToPhone.SetGrams(entry.id, grams)) },
                    onDelete = { link.send(ToPhone.Delete(entry.id)) },
                )
            }
        }
    }
}

/**
 * Запись: название, граммы и калории. Тап по граммам — правка на месте, Enter — сохранить;
 * крестик — удалить. Ширина колонок чисел постоянная: правка не двигает строку.
 */
@Composable
private fun EntryRow(entry: WebEntry, onGrams: (Int) -> Unit, onDelete: () -> Unit) {
    val colors = KcalTheme.colors
    var editing by remember(entry.id) { mutableStateOf(false) }
    // Открылось — сразу с фокусом и выделенным числом: новое пишется поверх, без лишнего клика.
    var typed by remember(entry.id, entry.grams, editing) {
        val grams = entry.grams.toString()
        mutableStateOf(TextFieldValue(grams, selection = TextRange(0, grams.length)))
    }
    val focus = remember { FocusRequester() }
    var focused by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.name,
            style = KcalTheme.type.body,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
            if (editing) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { value -> typed = value.copy(text = value.text.filter(Char::isDigit).take(5)) },
                    singleLine = true,
                    textStyle = KcalTheme.type.number,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp)
                        .focusRequester(focus)
                        // Ушёл из поля — правка отменена, как по Escape.
                        .onFocusChanged { state ->
                            if (focused && !state.isFocused) editing = false
                            focused = state.isFocused
                        }
                        .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> {
                                typed.text.toIntOrNull()?.takeIf { it in 1..WEB_GRAMS_MAX }?.let(onGrams)
                                editing = false
                                true
                            }
                            Key.Escape -> {
                                editing = false
                                true
                            }
                            else -> false
                        }
                    },
                )
            } else {
                Text(
                    "${entry.grams} г",
                    style = KcalTheme.type.number,
                    color = colors.text2,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { editing = true }.padding(8.dp),
                )
            }
        }
        Text(
            entry.kcal.toString(),
            style = KcalTheme.type.number,
            color = colors.text,
            modifier = Modifier.width(56.dp).padding(start = 8.dp),
        )
        Text(
            "×",
            style = KcalTheme.type.title,
            color = colors.text3,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Строка ввода: тот же поиск, что в приложении, подсказки — чипсами. Enter записывает
 * первую. Подсказки — только к тому, что сейчас в строке: ответ на старый запрос не годится.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(diary: PageState.Diary, link: PhoneLink) {
    val colors = KcalTheme.colors
    var input by remember { mutableStateOf("") }
    val found = diary.suggestionsFor(input)

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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Место под подсказки держится всегда: появление чипсов не двигает строку ввода.
        FlowRow(
            Modifier.fillMaxWidth().heightIn(min = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            found.forEachIndexed { index, item ->
                KcalChip(onClick = { add(index) }, background = if (index == 0) colors.bubble else colors.chip) {
                    Text(item.name, style = KcalTheme.type.chip, color = if (index == 0) colors.onBubble else colors.text)
                    ChipNumbers("· ${item.grams} г · ${item.kcal}", color = if (index == 0) colors.onBubble2 else colors.text3)
                }
            }
            if (diary.nothingFor(input)) {
                Text("Не нашлось", style = KcalTheme.type.chip, color = colors.text3, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            textStyle = KcalTheme.type.input,
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                    add(0)
                    true
                } else {
                    false
                }
            },
        )
    }
}
