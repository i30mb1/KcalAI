package n7.kcalai.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import n7.kcalai.model.MealType
import n7.kcalai.model.formatGrams
import n7.kcalai.model.title
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.MacroDot
import n7.kcalai.ui.colors
import n7.kcalai.ui.mascot.macro
import n7.kcalai.webapi.PageState
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WebSuggestion
import n7.kcalai.webapi.defaultMeal

/** Пауза в наборе перед поиском — как у строки ввода в приложении. */
private const val SEARCH_DEBOUNCE_MS = 200L

/**
 * Строка ввода: тот же поиск, что в приложении, подсказки — чипсами над ней.
 * ↑ и ↓ выбирают подсказку, Enter записывает выбранную, Esc очищает строку.
 * Клавиатурные действия — без анимации: их повторяют десятки раз подряд.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(diary: PageState.Diary, link: PhoneLink) {
    val colors = KcalTheme.colors
    var input by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val found = diary.suggestionsFor(input)
    var selected by remember(found) { mutableStateOf(0) }
    // В прошлый день «сейчас» нет: приём выбирает человек, по умолчанию — первый незаписанный.
    val past = diary.day.date < diary.day.today
    var meal by remember(diary.day.date) { mutableStateOf(diary.day.defaultMeal()) }

    LaunchedEffect(input) {
        if (input.isBlank()) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        link.send(ToPhone.Search(input.trim()))
    }

    fun add(index: Int) {
        val item = found.getOrNull(index) ?: return
        link.send(ToPhone.Add(query = input.trim(), index = index, grams = item.grams, meal = meal.takeIf { past }))
        input = ""
    }

    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (past) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MealType.entries.forEach { option ->
                    PageChip(option.title, primary = option == meal, onClick = { meal = option })
                }
            }
        }
        // Подсказки встают над полем, когда есть что подсказать, — и лента над ними уступает место.
        if (found.isNotEmpty() || diary.nothingFor(input)) {
            FlowRow(
                Modifier.fillMaxWidth(),
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
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(KcalShapes.input))
                .background(colors.surface)
                .border(1.dp, if (focused) colors.text.copy(alpha = 0.25f) else colors.line, RoundedCornerShape(KcalShapes.input))
                .padding(horizontal = 22.dp, vertical = 17.dp),
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
            modifier = Modifier.padding(start = 7.dp).widthIn(max = 280.dp),
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

/** Чипс-действие: главный — тёмный, как реплика человека, остальные — светлые. */
@Composable
fun PageChip(text: String, primary: Boolean, onClick: () -> Unit) {
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
