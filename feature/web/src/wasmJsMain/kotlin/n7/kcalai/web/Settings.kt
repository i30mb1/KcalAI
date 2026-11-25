package n7.kcalai.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import n7.kcalai.model.PageGrace
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.Macro
import n7.kcalai.ui.colors
import n7.kcalai.ui.letter
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WEB_GOAL_MAX_G
import n7.kcalai.webapi.WEB_NAME_MAX
import n7.kcalai.webapi.WebDay
import n7.kcalai.webapi.WebMacros

/**
 * Настройки страницы: имя, цель, пущенные браузеры. Сюда же встанет всё, что появится
 * потом: место под новое есть, а главный экран остаётся дневником.
 *
 * Окно поверх страницы; закрывается крестиком, клавишей Esc и кликом мимо.
 */
@Composable
fun SettingsPanel(day: WebDay, link: PhoneLink, worldAlways: Boolean, onWorldAlways: (Boolean) -> Unit, onClose: () -> Unit) {
    val colors = KcalTheme.colors
    val dismiss = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = dismiss, indication = null, onClick = onClose)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .enter()
                .clip(RoundedCornerShape(KcalShapes.sheet))
                .background(colors.surface)
                // Клик внутри окна — не «мимо»: окно от него не закрывается.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Настройки", style = KcalTheme.type.title, color = colors.text, modifier = Modifier.weight(1f))
                Text(
                    "×",
                    style = KcalTheme.type.title,
                    color = colors.text3,
                    modifier = Modifier
                        .press(RoundedCornerShape(10.dp), colors.text.copy(alpha = HOVER), onClose)
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
            NameSection(day.owner, onSave = { link.send(ToPhone.SetName(it)) })
            GoalSection(day.goalMacros, onSave = { link.send(ToPhone.SetGoal(it.prot, it.fat, it.carb)) })
            GraceSection(day.grace, onPick = { link.send(ToPhone.SetGrace(it)) })
            WorldSection(worldAlways, onWorldAlways)
            BrowsersSection(day, onRevoke = { link.send(ToPhone.Revoke(it)) }, onLeave = link::leave)
        }
    }
}

@Composable
private fun NameSection(current: String, onSave: (String) -> Unit) {
    var typed by remember(current) { mutableStateOf(current) }
    val changed = typed.trim().isNotEmpty() && typed.trim() != current
    Section("Имя") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(
                value = typed,
                onValue = { typed = it.take(WEB_NAME_MAX) },
                placeholder = "Как тебя зовут",
                onEnter = { if (changed) onSave(typed.trim()) },
                modifier = Modifier.weight(1f),
            )
            SaveChip(enabled = changed) { onSave(typed.trim()) }
        }
    }
}

/** Цель — граммами, как в шите приложения: калории из них складываются сами и всегда верно. */
@Composable
private fun GoalSection(current: WebMacros?, onSave: (WebMacros) -> Unit) {
    var prot by remember(current) { mutableStateOf(current?.prot?.toString().orEmpty()) }
    var fat by remember(current) { mutableStateOf(current?.fat?.toString().orEmpty()) }
    var carb by remember(current) { mutableStateOf(current?.carb?.toString().orEmpty()) }
    val goal = listOf(prot, fat, carb).map { it.toIntOrNull()?.takeIf { g -> g in 1..WEB_GOAL_MAX_G } }
        .takeIf { values -> values.all { it != null } }
        ?.let { (p, f, c) -> WebMacros(p!!, f!!, c!!) }
    val changed = goal != null && goal != current

    Section("Цель на день") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MacroField(Macro.PROTEIN, prot, { prot = it }, Modifier.weight(1f))
            MacroField(Macro.FAT, fat, { fat = it }, Modifier.weight(1f))
            MacroField(Macro.CARB, carb, { carb = it }, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                goal?.let { "${it.kcal} ккал" } ?: "Б, Ж и У в граммах",
                style = KcalTheme.type.number,
                color = if (goal != null) KcalTheme.colors.text else KcalTheme.colors.text3,
                modifier = Modifier.weight(1f),
            )
            SaveChip(enabled = changed) { goal?.let(onSave) }
        }
    }
}

@Composable
private fun MacroField(macro: Macro, value: String, onValue: (String) -> Unit, modifier: Modifier) {
    val palette = macro.colors()
    Row(
        modifier.clip(RoundedCornerShape(KcalShapes.tile)).background(palette.tile).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(macro.letter, style = KcalTheme.type.macro, color = palette.text)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = { onValue(it.filter(Char::isDigit).take(4)) },
            singleLine = true,
            textStyle = KcalTheme.type.number.copy(color = KcalTheme.colors.text),
            cursorBrush = SolidColor(KcalTheme.colors.text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Text("г", style = KcalTheme.type.time, color = palette.text)
    }
}

/**
 * Сколько страница живёт, когда телефон убран: мельком заблокировал — не заметит,
 * убрал надолго — связь закрывается. «Пока система не остановит» — честно: дольше
 * телефон приложение сам не держит.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GraceSection(current: PageGrace, onPick: (PageGrace) -> Unit) {
    Section("Когда телефон убран") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            PageGrace.entries.forEach { option ->
                PageChip(option.title, primary = option == current, onClick = { onPick(option) })
            }
        }
    }
}

/** Мир прогулки за дневником: только на ходу — или всегда, и тогда видно, докуда дошёл за день. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorldSection(always: Boolean, onPick: (Boolean) -> Unit) {
    Section("Мир прогулки") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            PageChip("Когда иду", primary = !always, onClick = { onPick(false) })
            PageChip("Всегда", primary = always, onClick = { onPick(true) })
        }
    }
}

/** Пущенные браузеры: чужой — «Отозвать», свой — «Выйти». Забытый в гостях компьютер дневник больше не откроет. */
@Composable
private fun BrowsersSection(day: WebDay, onRevoke: (Long) -> Unit, onLeave: () -> Unit) {
    val colors = KcalTheme.colors
    Section("Браузеры с доступом") {
        day.browsers.forEach { browser ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(browser.label, style = KcalTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
                if (browser.here) {
                    Text("этот", style = KcalTheme.type.caps, color = colors.text3, modifier = Modifier.padding(end = 10.dp))
                    TextAction("Выйти", onLeave)
                } else {
                    TextAction("Отозвать") { onRevoke(browser.id) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CapsLabel(title)
        content()
    }
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, placeholder: String, onEnter: () -> Unit, modifier: Modifier) {
    val colors = KcalTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(colors.bg)
            .border(1.dp, colors.line, RoundedCornerShape(KcalShapes.tile))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = KcalTheme.type.input, color = colors.text3)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = KcalTheme.type.input.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    onEnter()
                    true
                } else {
                    false
                }
            },
        )
    }
}

@Composable
private fun SaveChip(enabled: Boolean, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    Text(
        "Сохранить",
        style = KcalTheme.type.chip,
        color = if (enabled) colors.onBubble else colors.text3,
        modifier = Modifier
            .then(if (enabled) Modifier.press(RoundedCornerShape(KcalShapes.chip), colors.onBubble.copy(alpha = HOVER), onClick) else Modifier)
            .background(if (enabled) colors.bubble else colors.chip, RoundedCornerShape(KcalShapes.chip))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    Text(
        text,
        style = KcalTheme.type.chip,
        color = colors.error,
        modifier = Modifier
            .press(RoundedCornerShape(8.dp), colors.error.copy(alpha = HOVER), onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private const val HOVER = 0.08f
