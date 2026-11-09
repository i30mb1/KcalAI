package n7.kcalai.feature.diary

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.totals
import n7.kcalai.model.MealType
import n7.kcalai.personal.TdeeEstimate
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.KcalChip
import n7.kcalai.ui.Macro
import n7.kcalai.ui.MacroDot
import n7.kcalai.ui.WideButton
import n7.kcalai.ui.colors
import n7.kcalai.ui.tileLabel
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.mascot.MascotFigure
import n7.kcalai.ui.mascot.MascotPose
import n7.kcalai.ui.mascot.mascot
import n7.kcalai.model.Sex
import n7.kcalai.model.title

/**
 * Цель на день: белки, жиры и углеводы. Калории из них складываются сами.
 *
 * Отдельного поля калорий нет: оно давало сохранить невозможное, «200 ккал и 120 г
 * белка», хотя 120 г белка — это уже 480 ккал. Нет и подписей с пояснениями: макрос
 * узнаётся по персонажу, как в шапке, а итог крупно стоит сверху.
 *
 * Клавиатура не выскакивает сама: шит открывают посмотреть не реже, чем править,
 * и она закрывала бы половину экрана. Курсор встаёт, когда по полю тапнули.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalSheet(
    goal: DailyGoalEntity?,
    tdee: TdeeEstimate?,
    lastWeightGrams: Int?,
    onConfirm: (kcal: Int, prot: Int, fat: Int, carb: Int) -> Unit,
    onDismiss: () -> Unit,
    /** Пол из помощника — отдаётся вместе с сохранённой целью, чтобы персонажи не путали род. */
    onSex: (Sex) -> Unit = {},
) {
    val colors = KcalTheme.colors
    val start = remember { goalMacros(goal) }
    var prot by remember { mutableStateOf(start?.prot?.toString().orEmpty()) }
    var fat by remember { mutableStateOf(start?.fat?.toString().orEmpty()) }
    var carb by remember { mutableStateOf(start?.carb?.toString().orEmpty()) }
    val macros = GoalMacros(prot.toIntOrNull() ?: 0, fat.toIntOrNull() ?: 0, carb.toIntOrNull() ?: 0)

    // Пол из помощника запоминается, только если цель сохранили: посчитать
    // и закрыть шит — не повод что-то о человеке хранить.
    var helperSex by remember { mutableStateOf<Sex?>(null) }
    var focused by remember { mutableStateOf<Macro?>(null) }
    var helperOpen by remember { mutableStateOf(false) }

    Sheet(onDismiss) {
        SheetSpeech(goalSheetLine(focused, helperOpen, macros.kcal), Modifier.padding(bottom = 14.dp))

        // Итог трёх чисел — крупно, как остаток в шапке.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${macros.kcal}",
                style = KcalTheme.type.heroInput,
                color = if (macros.kcal > 0) colors.text else colors.text3,
            )
            Text(" ккал", style = KcalTheme.type.label, color = colors.text2, modifier = Modifier.padding(bottom = 4.dp))
        }

        // Поля проходятся с клавиатуры подряд: «далее» вместо «готово».
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val onFocus = { macro: Macro, has: Boolean ->
                if (has) focused = macro else if (focused == macro) focused = null
            }
            MacroField(Macro.PROTEIN, prot, { prot = it }, onFocus, Modifier.weight(1f))
            MacroField(Macro.FAT, fat, { fat = it }, onFocus, Modifier.weight(1f))
            MacroField(Macro.CARB, carb, { carb = it }, onFocus, Modifier.weight(1f), imeAction = ImeAction.Done)
        }

        // Расход по весу — одна строка, и только когда он посчитан: тап подставляет его в углеводы.
        tdee?.let { estimate ->
            KcalChip(
                onClick = { carb = carbsFor(estimate.kcalPerDay, macros.prot, macros.fat).toString() },
                background = colors.chip,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text("Расход по весу ≈ ${estimate.kcalPerDay} ккал", style = KcalTheme.type.chip, color = colors.text)
            }
        }

        GoalHelper(
            open = helperOpen,
            onToggle = { helperOpen = it },
            lastWeightGrams = lastWeightGrams,
            onSuggest = { suggestion, sex ->
                helperSex = sex
                prot = suggestion.prot.toString()
                fat = suggestion.fat.toString()
                carb = suggestion.carb.toString()
            },
            modifier = Modifier.padding(top = 12.dp),
        )

        WideButton(
            text = "Сохранить",
            enabled = macros.kcal > 0,
            onClick = {
                onConfirm(macros.kcal, macros.prot, macros.fat, macros.carb)
                helperSex?.let(onSex)
            },
            // Во всю ширину, как в шите правки: узкая кнопка у левого края
            // выглядела недоделанной и терялась под текстом.
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
        )
    }
}

/**
 * «Не знаете своих цифр?» — одна кнопка, а под ней, если раскрыть, посчитаем
 * по росту, весу и возрасту.
 *
 * Считает по формуле Миффлина — Сан Жеора и подставляет граммы в поля шита:
 * человек их видит, правит и сохраняет сам. Подписей у полей нет — их называют
 * единицы: «лет», «см», «кг».
 *
 * Рост и возраст не хранятся: подсказка нужна раз, на старте. Пол — хранится,
 * если цель сохранили: по нему персонажи говорят «ты ел» и «ты ела» правильно.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalHelper(
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    lastWeightGrams: Int?,
    onSuggest: (GoalSuggestion, Sex) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KcalTheme.colors
    val focusManager = LocalFocusManager.current

    var sex by remember { mutableStateOf<Sex?>(null) }
    var age by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    // Вес, уже записанный строкой «вес 82,4», второй раз не набирают.
    var weight by remember { mutableStateOf(lastWeightGrams?.let { formatKg(it).removeSuffix(" кг") }.orEmpty()) }
    var activity by remember { mutableStateOf(Activity.LIGHT) }
    var aim by remember { mutableStateOf(Aim.KEEP) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KcalChip(
            onClick = {
                onToggle(!open)
                focusManager.clearFocus()
            },
            background = if (open) colors.bubble else colors.chip,
        ) {
            Text("Не знаете своих цифр?", style = KcalTheme.type.chip, color = if (open) colors.onBubble else colors.text)
        }

        if (open) {
            Choices(Sex.entries, sex, Sex::title) { sex = it }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HelperField("возраст", age, { age = it }, "лет", Modifier.weight(1f))
                HelperField("рост", height, { height = it }, "см", Modifier.weight(1f))
                HelperField("вес", weight, { weight = it }, "кг", Modifier.weight(1f), decimal = true)
            }
            Choices(Activity.entries, activity, Activity::title) { activity = it }
            Choices(Aim.entries, aim, Aim::title) { aim = it }

            val body = bodyOrNull(sex, age, height, weight, activity, aim)
            WideButton(
                text = "Посчитать",
                enabled = body != null,
                onClick = {
                    body?.let { onSuggest(suggestGoal(it), it.sex) }
                    onToggle(false)
                    focusManager.clearFocus()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Персонаж говорит, что делать в шите: облокотился на пузырёк, как в ленте.
 * Сменилась реплика — сменяются и пузырёк, и тот, кто говорит.
 */
@Composable
private fun SheetSpeech(line: SheetLine, modifier: Modifier = Modifier) {
    val colors = KcalTheme.colors
    Crossfade(targetState = line, modifier = modifier, label = "goal-speech") { current ->
        Row(verticalAlignment = Alignment.Bottom) {
            MascotFigure(current.mascot, MascotPose.LEAN, Modifier.size(width = 44.dp, height = 37.dp))
            Text(
                current.text,
                style = KcalTheme.type.body,
                color = colors.text,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp))
                    .background(colors.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Выбор одного из нескольких — чипсами, выбранный тёмный. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T?, title: (T) -> String, onPick: (T) -> Unit) {
    val colors = KcalTheme.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        options.forEach { option ->
            val chosen = option == selected
            KcalChip(
                onClick = { onPick(option) },
                background = if (chosen) colors.bubble else colors.chip,
                modifier = Modifier.semantics { this.selected = chosen },
            ) {
                Text(title(option), style = KcalTheme.type.chip, color = if (chosen) colors.onBubble else colors.text)
            }
        }
    }
}

/** Поле помощника: число и единица. Что это за число, говорит единица; [label] — для экранного диктора. */
@Composable
private fun HelperField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
) {
    val colors = KcalTheme.colors

    Column(
        modifier
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(colors.chip)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = { text ->
                        onValueChange(
                            text.filter { it.isDigit() || (decimal && (it == ',' || it == '.')) }.take(HELPER_DIGITS)
                        )
                    },
                    textStyle = KcalTheme.type.number.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.text),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label, $unit" },
                )
                if (value.isEmpty()) {
                    Text("—", style = KcalTheme.type.number, color = colors.text3)
                }
            }
            Text(" $unit", style = KcalTheme.type.macro, color = colors.text3)
        }
    }
}

private const val HELPER_DIGITS = 5

/**
 * Плитка макроса с полем в граммах.
 *
 * Подписи нет: макрос узнаётся по персонажу и цвету, как в шапке. Цвета те же,
 * что там: это одна и та же величина.
 */
@Composable
private fun MacroField(
    macro: Macro,
    value: String,
    onValueChange: (String) -> Unit,
    onFocus: (Macro, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
) {
    val palette = macro.colors()

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(palette.tile)
            .padding(start = 11.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberInput(
            value = value,
            onValueChange = onValueChange,
            style = KcalTheme.type.number,
            placeholder = "0",
            imeAction = imeAction,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFocus(macro, it.isFocused) }
                .semantics { contentDescription = "${macro.tileLabel.lowercase()}, граммы" },
        )
        Text("г", style = KcalTheme.type.macro, color = palette.text)
        MascotFigure(macro.mascot, MascotPose.HOME, Modifier.padding(start = 4.dp).size(width = 30.dp, height = 25.dp))
    }
}

/**
 * Правка записи: вес, приём пищи, удаление.
 *
 * Вес правится кнопками и полем сразу: правка веса — самый чистый сигнал для
 * памяти личных порций, какой бывает, потому что человек видел подставленное
 * число и заменил его своим.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntrySheet(
    entry: DiaryEntryEntity,
    onSave: (grams: Int, meal: MealType) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KcalTheme.colors
    var grams by remember { mutableStateOf(entry.grams.toString()) }
    var meal by remember { mutableStateOf(entry.meal) }

    val value = grams.toIntOrNull()?.coerceAtLeast(MIN_GRAMS)
    val totals = entry.copy(grams = value ?: 0).totals()

    Sheet(onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MacroDot(dominantMacro(entry.totals()).colors().fill)
            Text(
                entry.displayName,
                style = KcalTheme.type.title,
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            Text(
                "на 100 г · ${entry.kcal100} ккал",
                style = KcalTheme.type.time,
                color = colors.text3,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            StepButton("−10") {
                grams = ((value ?: MIN_GRAMS) - GRAMS_STEP).coerceAtLeast(MIN_GRAMS).toString()
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(KcalShapes.tile))
                    .background(colors.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                NumberInput(
                    value = grams,
                    onValueChange = { grams = it },
                    style = KcalTheme.type.heroInput,
                    placeholder = "0",
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "г",
                    style = KcalTheme.type.label,
                    color = colors.text2,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }

            StepButton("+10") {
                grams = ((value ?: MIN_GRAMS) + GRAMS_STEP).toString()
            }
        }

        Text(
            "${totals.kcal} ккал  ·  Б ${formatCentigrams(totals.protCg)}  " +
                "·  Ж ${formatCentigrams(totals.fatCg)}  ·  У ${formatCentigrams(totals.carbCg)}",
            style = KcalTheme.type.macro,
            color = colors.text2,
            modifier = Modifier.padding(top = 12.dp),
        )

        CapsLabel("приём пищи", Modifier.padding(top = 18.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MealType.entries.forEach { candidate ->
                val selected = candidate == meal
                KcalChip(
                    onClick = { meal = candidate },
                    background = if (selected) colors.bubble else colors.chip,
                ) {
                    Text(
                        candidate.title,
                        style = KcalTheme.type.chip,
                        color = if (selected) colors.onBubble else colors.text,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // Удаление — квадратной кнопкой и без подтверждения: в ленте после
            // него встаёт «Удалил… Вернуть», а диалог «вы уверены?» на каждой
            // опечатке дороже самой опечатки.
            //
            // Корзина, а не крестик: крестик рядом с «Сохранить» читается как
            // «закрыть без изменений», и человек, передумавший править вес,
            // стирал запись.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(KcalShapes.tile))
                    .background(colors.error.copy(alpha = 0.12f))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Удалить ${entry.displayName}",
                    tint = colors.error,
                    modifier = Modifier.size(20.dp),
                )
            }

            WideButton(
                text = "Сохранить",
                enabled = value != null,
                onClick = { value?.let { onSave(it, meal) } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Шаг веса: два тапа быстрее, чем вызвать клавиатуру и напечатать «210». */
@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(colors.chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = KcalTheme.type.number, color = colors.text)
    }
}

/** Общая обвязка шита: своя поверхность, свой радиус, отступы под навигацию. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = KcalTheme.colors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bg,
        contentColor = colors.text,
        scrimColor = Color(0xFF1C1914).copy(alpha = 0.4f),
        shape = RoundedCornerShape(topStart = KcalShapes.sheet, topEnd = KcalShapes.sheet),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            content()
        }
    }
}

/**
 * Поле для целого числа.
 *
 * `BasicTextField`, а не `OutlinedTextField`: в обводке и плавающей подписи
 * Material нет ничего от этого экрана, а кегль числа здесь задаётся по месту —
 * от 15sp на плитке до 34sp у веса.
 */
@Composable
private fun NumberInput(
    value: String,
    onValueChange: (String) -> Unit,
    style: TextStyle,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
) {
    val colors = KcalTheme.colors

    // Курсора наготове нет: клавиатура появляется, только когда по полю тапнули.
    Box(modifier) {
        BasicTextField(
            value = value,
            onValueChange = { text -> onValueChange(text.filter(Char::isDigit).take(MAX_DIGITS)) },
            textStyle = style.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = imeAction,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
            Text(placeholder, style = style, color = colors.text3)
        }
    }
}

private const val GRAMS_STEP = 10
private const val MIN_GRAMS = 1
private const val MAX_DIGITS = 5
