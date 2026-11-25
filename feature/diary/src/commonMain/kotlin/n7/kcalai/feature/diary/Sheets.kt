package n7.kcalai.feature.diary

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.totals
import n7.kcalai.model.FoodRef
import n7.kcalai.model.GoalMacros
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.Nutriments
import n7.kcalai.model.Sex
import n7.kcalai.model.formatCentigrams
import n7.kcalai.model.parseFoodRef
import n7.kcalai.model.title
import n7.kcalai.personal.TdeeEstimate
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.KcalChip
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.Macro
import n7.kcalai.ui.MacroDot
import n7.kcalai.ui.SproutIcon
import n7.kcalai.ui.WideButton
import n7.kcalai.ui.colors
import n7.kcalai.ui.letter
import n7.kcalai.ui.mascot.mascot
import n7.kcalai.ui.tileLabel

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
    // Цели ещё нет — новичок своих граммов обычно не знает: помощник открыт сразу, поля граммов — для тех, кто знает.
    var helperOpen by remember { mutableStateOf(goal == null) }

    // Своя сцена в шите: он — отдельное окно, и персонажи главного экрана над ним
    // не рисуются. Здесь они перебегают с места на место так же, как там.
    val anchors = remember { MascotAnchors() }
    val pointer = remember { StagePointer() }
    val line = goalSheetLine(focused, helperOpen, macros.kcal)
    val places = goalSheetPlaces(line)

    Sheet(onDismiss) {
    CompositionLocalProvider(LocalMascotAnchors provides anchors) {
    Box(Modifier.observeTouches(pointer)) {
    Column {
        SheetSpeech(line, Modifier.padding(bottom = 14.dp))

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
            val away = emptyHomes(places)
            MacroField(Macro.PROTEIN, prot, { prot = it }, onFocus, Mascot.EGG !in away, Modifier.weight(1f))
            MacroField(Macro.FAT, fat, { fat = it }, onFocus, Mascot.BUTTER !in away, Modifier.weight(1f))
            MacroField(
                Macro.CARB, carb, { carb = it }, onFocus, Mascot.GRAIN !in away, Modifier.weight(1f),
                imeAction = ImeAction.Done,
            )
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
        MascotStage(
            places = places,
            anchors = anchors,
            pointer = pointer,
            // Набирают граммы — персонажи косятся вниз, как на главном экране.
            input = prot + fat + carb,
            speech = emptyList(),
            cue = null,
            night = false,
            onEvent = {},
            modifier = Modifier.matchParentSize(),
        )
    }
    }
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
                HelperField("возраст", age, { age = it }, "лет", Modifier.weight(1f), maxLength = 3)
                HelperField("рост", height, { height = it }, "см", Modifier.weight(1f), maxLength = 3)
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
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        // Место говорящего: он сам перебегает сюда со сцены шита.
        Box(Modifier.size(width = 44.dp, height = 37.dp).mascotAnchor(SHEET_SPEECH_ANCHOR))
        // Пузырёк всегда в две строки: реплики разной длины, и без запаса всё,
        // что ниже, прыгало бы при каждой смене.
        Crossfade(
            targetState = line.text,
            label = "goal-speech",
            modifier = Modifier
                .padding(start = 4.dp)
                .weight(1f)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp))
                .background(colors.bg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) { text ->
            Text(text, style = KcalTheme.type.body, color = colors.text, minLines = SPEECH_LINES, maxLines = SPEECH_LINES)
        }
    }
}

private const val SPEECH_LINES = 2

/** Выбор одного из нескольких — чипсами, выбранный тёмный. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T?, title: (T) -> String, onPick: (T) -> Unit) {
    val colors = KcalTheme.colors
    // Одна категория — одна строка равных частей: длинный вариант переносится внутри своей части,
    // а не уезжает на вторую строку отдельно от соседей. Высота у всех — по самому высокому.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { option ->
            val chosen = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(KcalShapes.chip))
                    .background(if (chosen) colors.bubble else colors.chip)
                    .clickable { onPick(option) }
                    .semantics { this.selected = chosen }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title(option),
                    style = KcalTheme.type.chip,
                    color = if (chosen) colors.onBubble else colors.text,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
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
    /** Сколько знаков помещается: возраст и рост — три, вес с запятой — пять («82,5»). */
    maxLength: Int = HELPER_DIGITS,
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
                            text.filter { it.isDigit() || (decimal && (it == ',' || it == '.')) }.take(maxLength)
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
    mascotShown: Boolean,
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
        // Дом персонажа в поле: сам он рисуется сценой шита поверх. Ушёл к реплике —
        // на пустом месте буква макроса, как подпись пустого домика в шапке.
        Box(
            Modifier.padding(start = 4.dp).size(width = 30.dp, height = 25.dp).mascotAnchor(homeAnchor(macro.mascot)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            androidx.compose.animation.AnimatedVisibility(visible = !mascotShown, enter = fadeIn(), exit = fadeOut()) {
                Text(macro.letter, style = KcalTheme.type.capsTile, color = palette.text)
            }
        }
    }
}

/**
 * Правка записи — две разные правки в одном шите.
 *
 * Порция: вес, приём пищи, удаление. Это про то, сколько и когда человек съел,
 * и правится молча. Вес правится кнопками и полем сразу: правка веса — самый
 * чистый сигнал для памяти личных порций, потому что человек видел подставленное
 * число и заменил его своим.
 *
 * Продукт: цифры на 100 г. Это про саму еду, а не про порцию, и правка уходит
 * дальше дневника — у продукта с кодом в общую базу. Поэтому она отдельным шагом
 * за карточкой продукта, и ведёт её Яйцо: оно говорит, куда уйдут цифры.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntrySheet(
    entry: DiaryEntryEntity,
    onSave: (grams: Int, meal: MealType) -> Unit,
    onSaveProduct: (name: String, per100: Nutriments) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Порция переживает переход к продукту и обратно: набранный вес не сбрасывается.
    var grams by remember { mutableStateOf(entry.grams.toString()) }
    var meal by remember { mutableStateOf(entry.meal) }
    var productMode by remember { mutableStateOf(false) }
    val anchors = remember { MascotAnchors() }
    val pointer = remember { StagePointer() }

    Sheet(onDismiss) {
    CompositionLocalProvider(LocalMascotAnchors provides anchors) {
    Box(Modifier.observeTouches(pointer)) {
        if (productMode) {
            ProductEditor(
                entry = entry,
                onSave = { name, per100 ->
                    onSaveProduct(name, per100)
                    productMode = false
                },
                onBack = { productMode = false },
            )
        } else {
            PortionEditor(
                entry = entry,
                grams = grams,
                onGrams = { grams = it },
                meal = meal,
                onMeal = { meal = it },
                onOpenProduct = { productMode = true },
                onSave = onSave,
                onDelete = onDelete,
            )
        }
        MascotStage(
            places = entrySheetPlaces(productMode),
            anchors = anchors,
            pointer = pointer,
            input = grams,
            speech = emptyList(),
            cue = null,
            night = false,
            onEvent = {},
            modifier = Modifier.matchParentSize(),
        )
    }
    }
    }
}

/** Порция: сколько и в какой приём. Продукт — карточкой, тап по ней открывает его правку. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortionEditor(
    entry: DiaryEntryEntity,
    grams: String,
    onGrams: (String) -> Unit,
    meal: MealType,
    onMeal: (MealType) -> Unit,
    onOpenProduct: () -> Unit,
    onSave: (grams: Int, meal: MealType) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = KcalTheme.colors
    val value = grams.toIntOrNull()?.coerceAtLeast(MIN_GRAMS)
    val totals = entry.copy(grams = value ?: 0).totals()

    Column {
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
        }

        ProductCard(entry, onClick = onOpenProduct, modifier = Modifier.padding(top = 12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            StepButton("−10") {
                onGrams(((value ?: MIN_GRAMS) - GRAMS_STEP).coerceAtLeast(MIN_GRAMS).toString())
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(KcalShapes.tile))
                    .background(colors.bg)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                NumberInput(
                    value = grams,
                    onValueChange = onGrams,
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
                onGrams(((value ?: MIN_GRAMS) + GRAMS_STEP).toString())
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
                    onClick = { onMeal(candidate) },
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

/**
 * Продукт на 100 г — карточкой под названием. Порция ниже считается из этих цифр,
 * а карандаш говорит, что их можно поправить: подпись «изменить продукт» не нужна.
 */
@Composable
private fun ProductCard(entry: DiaryEntryEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KcalTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(colors.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "100 г  ·  ${entry.kcal100} ккал  ·  Б ${formatCentigrams(entry.prot100)}  " +
                "·  Ж ${formatCentigrams(entry.fat100)}  ·  У ${formatCentigrams(entry.carb100)}",
            style = KcalTheme.type.macro,
            color = colors.text2,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        entry.fiber100?.let { fiber ->
            SproutIcon(colors.fiber.fill, size = 11.dp)
            Text(
                formatCentigrams(fiber),
                style = KcalTheme.type.macro,
                color = colors.fiber.text,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
        Icon(
            Icons.Outlined.Edit,
            contentDescription = "Поправить продукт",
            tint = colors.text3,
            modifier = Modifier.padding(start = 10.dp).size(16.dp),
        )
    }
}

/**
 * Правка самого продукта: цифры на 100 г по упаковке. Яйцо говорит, куда они уйдут.
 *
 * Поля заводятся от записи: после сохранения шит возвращается к порции уже
 * с поправленным продуктом, и следующая правка начинается с новых цифр.
 */
@Composable
private fun ProductEditor(entry: DiaryEntryEntity, onSave: (name: String, per100: Nutriments) -> Unit, onBack: () -> Unit) {
    val colors = KcalTheme.colors
    var name by remember(entry) { mutableStateOf(entry.displayName) }
    var kcal by remember(entry) { mutableStateOf(entry.kcal100.toString()) }
    var prot by remember(entry) { mutableStateOf(entry.prot100.gramsField()) }
    var fat by remember(entry) { mutableStateOf(entry.fat100.gramsField()) }
    var carb by remember(entry) { mutableStateOf(entry.carb100.gramsField()) }
    var fiber by remember(entry) { mutableStateOf(entry.fiber100?.gramsField() ?: "") }
    val per100 = per100Nutriments(entry, kcal, prot, fat, carb, fiber)

    Column {
        SheetSpeech(
            productSpeech(hasBarcode = parseFoodRef(entry.foodRef) is FoodRef.Barcode),
            Modifier.padding(bottom = 14.dp),
        )
        NameField(name, onValueChange = { name = it })

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            NutrientField("ккал на 100 г", kcal, { kcal = it }, Modifier.weight(2f), decimal = false)
            // Клетчатку можно не знать: пустое поле — «неизвестно», а не ноль.
            NutrientField(
                label = "",
                value = fiber,
                onValueChange = { fiber = it },
                modifier = Modifier.weight(1f),
                icon = { SproutIcon(colors.fiber.fill, size = 12.dp) },
                // Пусто — неизвестна, и подсказка не выдаёт это за ноль.
                placeholder = "–",
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            NutrientField("Б", prot, { prot = it }, Modifier.weight(1f))
            NutrientField("Ж", fat, { fat = it }, Modifier.weight(1f))
            NutrientField("У", carb, { carb = it }, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(KcalShapes.tile))
                    .background(colors.chip)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "К порции",
                    tint = colors.text,
                    modifier = Modifier.size(20.dp),
                )
            }
            WideButton(
                text = "Сохранить",
                enabled = per100 != null && name.isNotBlank(),
                onClick = { if (per100 != null && name.isNotBlank()) onSave(name, per100) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Название продукта — полем во всю ширину, тем же шрифтом, что заголовок шита. Клавиатура — только по тапу. */
@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit) {
    val colors = KcalTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = KcalTheme.type.title.copy(color = colors.text),
        cursorBrush = SolidColor(colors.text),
        maxLines = 2,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(colors.bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** Реплика Яйца в правке продукта: куда уйдут поправленные цифры. */
internal fun productSpeech(hasBarcode: Boolean): SheetLine = SheetLine(
    Mascot.EGG,
    if (hasBarcode) "Сверь с упаковкой и поправь. Сохраню и отправлю в общую базу."
    else "Сверь с упаковкой и поправь. Пересчитаю запись в дневнике.",
)

/** Кто где стоит в шите записи: в правке продукта Яйцо у реплики, в остальное время все за сценой. */
internal fun entrySheetPlaces(productMode: Boolean): Map<Mascot, MascotPlace> =
    Mascot.entries.associateWith { mascot ->
        if (productMode && mascot == Mascot.EGG) MascotPlace.Reply(SHEET_SPEECH_ANCHOR) else MascotPlace.Offstage
    }

/**
 * Свой продукт из ненайденного слова — форма без камеры.
 *
 * Те же поля, что у правки продукта: название уже вписано тем, что набрано, дальше
 * цифры на 100 г и штрих-код. Код необязателен — у маминого пирога его нет, — и вписывается
 * руками или снимается камерой по кнопке рядом. Ведёт Яйцо — хозяин общей базы.
 * Клавиатура сама не открывается: только по тапу в поле.
 */
@Composable
fun NewProductSheet(
    form: NewProductForm,
    onChange: (NewProductForm) -> Unit,
    onScanCode: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet(onDismiss) {
        NewProductPanel(form, onChange, onScanCode, onSave)
    }
}

/** Содержимое формы без шита вокруг — его же показывает превью: всплывающий шит оно не рисует. */
@Composable
fun NewProductPanel(
    form: NewProductForm,
    onChange: (NewProductForm) -> Unit,
    onScanCode: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = KcalTheme.colors
    val anchors = remember { MascotAnchors() }
    val pointer = remember { StagePointer() }

    CompositionLocalProvider(LocalMascotAnchors provides anchors) {
    Box(Modifier.observeTouches(pointer)) {
        Column {
            SheetSpeech(newProductSpeech(form), Modifier.padding(bottom = 14.dp))
            NameField(form.name, onValueChange = { onChange(form.copy(name = it)) })

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                NutrientField("ккал на 100 г", form.kcal, { onChange(form.copy(kcal = it)) }, Modifier.weight(2f), decimal = false)
                // Клетчатку можно не знать: пустое поле — «неизвестно», а не ноль.
                NutrientField(
                    label = "",
                    value = form.fiber,
                    onValueChange = { onChange(form.copy(fiber = it)) },
                    modifier = Modifier.weight(1f),
                    icon = { SproutIcon(colors.fiber.fill, size = 12.dp) },
                    placeholder = "–",
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                NutrientField("Б", form.prot, { onChange(form.copy(prot = it)) }, Modifier.weight(1f))
                NutrientField("Ж", form.fat, { onChange(form.copy(fat = it)) }, Modifier.weight(1f))
                NutrientField("У", form.carb, { onChange(form.copy(carb = it)) }, Modifier.weight(1f))
            }

            BarcodeField(
                value = form.barcode,
                wrong = form.barcodeWrong,
                onValueChange = { onChange(form.copy(barcode = it)) },
                onScan = onScanCode,
                modifier = Modifier.padding(top = 7.dp),
            )

            WideButton(
                text = "Сохранить",
                enabled = form.canSave,
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            )
        }
        MascotStage(
            places = newProductSheetPlaces(),
            anchors = anchors,
            pointer = pointer,
            input = form.name,
            speech = emptyList(),
            cue = null,
            night = false,
            onEvent = {},
            modifier = Modifier.matchParentSize(),
        )
    }
    }
}

/** В форме своего продукта Яйцо у реплики, остальные за сценой. */
internal fun newProductSheetPlaces(): Map<Mascot, MascotPlace> =
    Mascot.entries.associateWith { mascot ->
        if (mascot == Mascot.EGG) MascotPlace.Reply(SHEET_SPEECH_ANCHOR) else MascotPlace.Offstage
    }

/**
 * Штрих-код: цифры руками или камерой по кнопке. Опечатка в контрольной цифре красит
 * цифры, а не добавляет строку под полем: форма не прыгает, пока код набирают.
 */
@Composable
private fun BarcodeField(
    value: String,
    wrong: Boolean,
    onValueChange: (String) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KcalTheme.colors
    val style = KcalTheme.type.macro.copy(color = if (wrong) colors.error else colors.text)

    // Высота — как у полей цифр над ним, и кнопка камеры ей в рост: форма одной сеткой.
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(KcalShapes.tile))
                .background(colors.bg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { text -> onValueChange(text.filter(Char::isDigit).take(BARCODE_DIGITS)) },
                textStyle = style,
                cursorBrush = SolidColor(colors.text),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "штрих-код" },
            )
            if (value.isEmpty()) Text("штрих-код", style = KcalTheme.type.label, color = colors.text3)
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(KcalShapes.tile))
                .background(colors.chip)
                .clickable(onClick = onScan),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.QrCodeScanner,
                contentDescription = "Снять штрих-код",
                tint = colors.text,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** EAN-13 — самый длинный код на пачке; GTIN-14 — на коробке, и на него тоже хватит. */
private const val BARCODE_DIGITS = 14

/** Поле цифры на 100 г: подпись слева от значения, без рамки — как у веса. */
@Composable
private fun NutrientField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    icon: (@Composable () -> Unit)? = null,
    decimal: Boolean = true,
    placeholder: String = "0",
) {
    val colors = KcalTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(colors.bg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberInput(
            value = value,
            onValueChange = onValueChange,
            style = KcalTheme.type.macro,
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
            decimal = decimal,
        )
        if (icon != null) {
            icon()
        } else {
            Text(label, style = KcalTheme.type.label, color = colors.text2)
        }
    }
}

/**
 * Цифры на 100 г из полей. Поле, которого человек не трогал, сохраняет свои сотые:
 * иначе «2,95» округлилось бы до «3» на одном только открытии шита, и правка ушла бы
 * к людям, которой никто не делал.
 */
private fun per100Nutriments(
    entry: DiaryEntryEntity,
    kcal: String,
    prot: String,
    fat: String,
    carb: String,
    fiber: String,
): Nutriments? {
    val kcalValue = kcal.toIntOrNull()?.takeIf { it >= 0 } ?: return null
    // Клетчатку можно не знать: пустое поле — не ноль, а честное «неизвестно».
    val fiberValue = if (fiber.isBlank()) null else centigrams(fiber, entry.fiber100) ?: return null
    return Nutriments(
        kcal100 = kcalValue,
        prot100 = centigrams(prot, entry.prot100) ?: return null,
        fat100 = centigrams(fat, entry.fat100) ?: return null,
        carb100 = centigrams(carb, entry.carb100) ?: return null,
        fiber100 = fiberValue,
    )
}

private fun centigrams(text: String, original: Int?): Int? =
    if (original != null && text == original.gramsField()) original
    else text.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 100).roundToInt() }

/** Сотые грамма в поле: «2,9», «2,95», «3» — без хвостовых нулей. */
private fun Int.gramsField(): String =
    if (this % 100 == 0) (this / 100).toString()
    else "${this / 100},${(this % 100).toString().padStart(2, '0').trimEnd('0')}"

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
        // Шит светлее ленты, а затемнение за ним гуще: одного цвета с перепиской
        // он с ней сливался, и было непонятно, где кончается окно. Поля внутри —
        // наоборот, цвета ленты: углублены в светлую карточку.
        containerColor = colors.surface,
        contentColor = colors.text,
        scrimColor = Color(0xFF1C1914).copy(alpha = 0.6f),
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
    /** Граммы на 100 г с дробью — «2,9»; вес порции и калории — целые. */
    decimal: Boolean = false,
) {
    val colors = KcalTheme.colors

    // Курсора наготове нет: клавиатура появляется, только когда по полю тапнули.
    Box(modifier) {
        BasicTextField(
            value = value,
            onValueChange = { text ->
                onValueChange(if (decimal) decimalInput(text) else text.filter(Char::isDigit).take(MAX_DIGITS))
            },
            textStyle = style.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                imeAction = imeAction,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
            Text(placeholder, style = style, color = colors.text3)
        }
    }
}

/**
 * Граммы с дробью из того, что набрано: цифры и одна запятая, не больше двух знаков
 * после неё — цифры хранятся в сотых грамма. Точка становится запятой: на разных
 * клавиатурах разделитель разный, а читается у нас запятая.
 */
internal fun decimalInput(text: String): String {
    val out = StringBuilder()
    var separator = -1
    for (char in text) {
        when {
            char.isDigit() -> if (separator < 0 || out.length - separator <= DECIMALS) out.append(char)
            (char == ',' || char == '.') && separator < 0 -> {
                separator = out.length
                out.append(',')
            }
        }
    }
    return out.toString().take(MAX_DIGITS + 1 + DECIMALS)
}

private const val GRAMS_STEP = 10
private const val MIN_GRAMS = 1
private const val MAX_DIGITS = 5
private const val DECIMALS = 2
