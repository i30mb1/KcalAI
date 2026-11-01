package n7.kcalai.feature.diary

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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.totals
import n7.kcalai.model.MealType
import n7.kcalai.personal.TdeeEstimate
import n7.kcalai.personal.TdeeEstimator
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.KcalChip
import n7.kcalai.ui.Macro
import n7.kcalai.ui.MacroDot
import n7.kcalai.ui.WideButton
import n7.kcalai.ui.colors
import n7.kcalai.ui.tileLabel
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.model.Sex

/**
 * Цель на день плюс — и это главное здесь — оценка реального расхода.
 *
 * Формулы вроде Миффлина-Сан Жеора считают расход по росту и весу и ошибаются
 * на ±20%. Карточка [TdeeEstimate] считает его по тому, что человек реально съел
 * и что реально произошло с его весом, — и подставляется в цель одним тапом.
 *
 * Курсор встаёт в то поле, по которому человек ткнул в шапке: шит открывается
 * из четырёх разных мест, и заставлять его каждый раз искать нужное поле незачем.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalSheet(
    goal: DailyGoalEntity?,
    tdee: TdeeEstimate?,
    lastWeightGrams: Int?,
    date: LocalDate,
    focus: GoalField,
    onConfirm: (kcal: Int, prot: Int, fat: Int, carb: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var kcal by remember { mutableStateOf(goal?.kcal?.toString().orEmpty()) }
    var prot by remember { mutableStateOf(goal?.prot?.toString().orEmpty()) }
    var fat by remember { mutableStateOf(goal?.fat?.toString().orEmpty()) }
    var carb by remember { mutableStateOf(goal?.carb?.toString().orEmpty()) }

    // Новичку клавиатура сразу ни к чему: своих цифр он может не знать, и
    // поле с курсором наготове закрывало бы второй путь — «посчитаем».
    // Тап по конкретной плитке — другое дело: человек показал, что править.
    val autofocus = goal != null || focus != GoalField.KCAL

    Sheet(onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Цель на день", style = KcalTheme.type.title, color = KcalTheme.colors.text)
            Spacer(Modifier.width(12.dp))
            CapsLabel("с ${formatDayMonthShort(date)}", Modifier.weight(1f))
        }

        GoalHelperCard(
            lastWeightGrams = lastWeightGrams,
            // Новичок видит помощь раскрытой: без цели ему почти наверняка нужна она.
            initiallyOpen = goal == null && focus == GoalField.KCAL,
            onSuggest = { suggestion ->
                kcal = suggestion.kcal.toString()
                prot = suggestion.prot.toString()
                fat = suggestion.fat.toString()
                carb = suggestion.carb.toString()
            },
            modifier = Modifier.padding(top = 14.dp),
        )

        // Поля проходятся с клавиатуры подряд — калории, белки, жиры, углеводы:
        // «далее» вместо «готово», чтобы не тянуться пальцем к каждой плитке.
        KcalField(
            value = kcal,
            onValueChange = { kcal = it },
            focused = autofocus && focus == GoalField.KCAL,
            modifier = Modifier.padding(top = 12.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            MacroField(Macro.PROTEIN, prot, { prot = it }, autofocus && focus == GoalField.PROT, Modifier.weight(1f))
            MacroField(Macro.FAT, fat, { fat = it }, autofocus && focus == GoalField.FAT, Modifier.weight(1f))
            MacroField(
                Macro.CARB, carb, { carb = it }, autofocus && focus == GoalField.CARB, Modifier.weight(1f),
                imeAction = ImeAction.Done,
            )
        }

        MacroSplit(
            protGrams = prot.toIntOrNull() ?: 0,
            fatGrams = fat.toIntOrNull() ?: 0,
            carbGrams = carb.toIntOrNull() ?: 0,
            modifier = Modifier.padding(top = 14.dp),
        )

        TdeeCard(
            tdee = tdee,
            goalKcal = kcal.toIntOrNull(),
            onApply = { kcal = it.toString() },
            modifier = Modifier.padding(top = 14.dp),
        )

        Text(
            "Новая цель действует с сегодняшнего дня. Прошлые дни и линия на графике " +
                "за них не меняются.",
            style = KcalTheme.type.body,
            color = KcalTheme.colors.text2,
            modifier = Modifier.padding(top = 12.dp),
        )

        WideButton(
            text = "Сохранить",
            // Калории обязательны, макросы — нет: без них считается только остаток
            // по калориям, и это уже полезно.
            enabled = kcal.toIntOrNull()?.let { it > 0 } == true,
            onClick = {
                onConfirm(
                    kcal.toIntOrNull() ?: 0,
                    prot.toIntOrNull() ?: 0,
                    fat.toIntOrNull() ?: 0,
                    carb.toIntOrNull() ?: 0,
                )
            },
            // Во всю ширину, как в шите правки: узкая кнопка у левого края
            // выглядела недоделанной и терялась под текстом.
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
        )
    }
}

/**
 * «Не знаете своих цифр? Посчитаем по росту, весу и возрасту».
 *
 * Второй путь к цели рядом с первым — вписать самому. Считает по формуле
 * Миффлина — Сан Жеора и подставляет числа в поля шита: человек их видит,
 * правит и сохраняет сам. Формула ошибается на ±20%, и это сказано прямо:
 * через две недели взвешиваний карточка ниже предложит расход по факту.
 *
 * Рост и возраст не хранятся: подсказка нужна раз, на старте, а заводить ради
 * неё профиль значило бы менять схему базы.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalHelperCard(
    lastWeightGrams: Int?,
    initiallyOpen: Boolean,
    onSuggest: (GoalSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KcalTheme.colors
    val type = KcalTheme.type
    val focusManager = LocalFocusManager.current

    var open by remember { mutableStateOf(initiallyOpen) }
    var applied by remember { mutableStateOf(false) }
    var sex by remember { mutableStateOf<Sex?>(null) }
    var age by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    // Вес, уже записанный строкой «вес 82,4», второй раз не набирают.
    var weight by remember { mutableStateOf(lastWeightGrams?.let { formatKg(it).removeSuffix(" кг") }.orEmpty()) }
    var activity by remember { mutableStateOf(Activity.LIGHT) }
    var aim by remember { mutableStateOf(Aim.KEEP) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    open = !open
                    focusManager.clearFocus()
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Не знаете своих цифр?", style = type.bubbleTitle, color = colors.text)
                Text(
                    if (applied && !open) {
                        "Подставили по формуле — поправьте, если нужно"
                    } else {
                        "Посчитаем по росту, весу и возрасту"
                    },
                    style = type.body,
                    color = colors.text2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (open) "Свернуть" else "Раскрыть",
                tint = colors.text3,
            )
        }

        if (open) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Choices(Sex.entries, sex, Sex::title) { sex = it }

                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    HelperField("возраст", age, { age = it }, "лет", Modifier.weight(1f))
                    HelperField("рост", height, { height = it }, "см", Modifier.weight(1f))
                    HelperField("вес", weight, { weight = it }, "кг", Modifier.weight(1f), decimal = true)
                }

                CapsLabel("движение")
                Choices(Activity.entries, activity, Activity::title) { activity = it }

                CapsLabel("цель")
                Choices(Aim.entries, aim, Aim::title) { aim = it }

                val body = bodyOrNull(sex, age, height, weight, activity, aim)
                WideButton(
                    text = "Посчитать",
                    enabled = body != null,
                    onClick = {
                        body?.let { onSuggest(suggestGoal(it)) }
                        applied = true
                        open = false
                        focusManager.clearFocus()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Это оценка по формуле Миффлина — Сан Жеора, она ошибается на ±20%. " +
                        "Через две недели взвешиваний здесь появится ваш расход по факту.",
                    style = type.time,
                    color = colors.text3,
                )
            }
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

/** Поле помощи с целью: подпись сверху, число и единица. */
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CapsLabel(label)
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
                    modifier = Modifier.fillMaxWidth(),
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

/** Калории цели — крупным числом: это та величина, ради которой шит открывают. */
@Composable
private fun KcalField(
    value: String,
    onValueChange: (String) -> Unit,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = KcalTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        NumberInput(
            value = value,
            onValueChange = onValueChange,
            style = KcalTheme.type.heroInput,
            focused = focused,
            placeholder = "0",
            imeAction = ImeAction.Next,
            modifier = Modifier.weight(1f),
        )
        Text(
            "ккал в день",
            style = KcalTheme.type.label,
            color = colors.text2,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

/** Плитка макроса с полем в граммах. Цвета те же, что в шапке: это одна и та же величина. */
@Composable
private fun MacroField(
    macro: Macro,
    value: String,
    onValueChange: (String) -> Unit,
    focused: Boolean,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
) {
    val palette = macro.colors()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(palette.tile)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(macro.tileLabel, style = KcalTheme.type.capsTile, color = palette.text)
        Row(verticalAlignment = Alignment.Bottom) {
            NumberInput(
                value = value,
                onValueChange = onValueChange,
                style = KcalTheme.type.number,
                focused = focused,
                placeholder = "0",
                imeAction = imeAction,
                modifier = Modifier.weight(1f),
            )
            Text(" г", style = KcalTheme.type.macro, color = palette.text)
        }
    }
}

/**
 * Раскладка цели по калориям.
 *
 * Граммы макросов и калории — величины разного порядка, и человек не обязан
 * держать в голове, что в жире девять килокалорий на грамм, а в белке четыре.
 * Полоска показывает, во что складывается набранное, до того, как цель сохранена.
 */
@Composable
private fun MacroSplit(
    protGrams: Int,
    fatGrams: Int,
    carbGrams: Int,
    modifier: Modifier = Modifier,
) {
    val colors = KcalTheme.colors
    val prot = protGrams * KCAL_PER_PROT
    val fat = fatGrams * KCAL_PER_FAT
    val carb = carbGrams * KCAL_PER_CARB
    val total = prot + fat + carb
    if (total <= 0) return

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf(
                Macro.PROTEIN to prot,
                Macro.FAT to fat,
                Macro.CARB to carb,
            ).forEach { (macro, kcal) ->
                if (kcal > 0) {
                    Box(
                        Modifier
                            .weight(kcal.toFloat())
                            .fillMaxWidth()
                            .height(7.dp)
                            .background(macro.colors().fill)
                    )
                }
            }
        }
        Text(
            "Б ${percent(prot, total)}%  ·  Ж ${percent(fat, total)}%  ·  " +
                "У ${percent(carb, total)}%  ·  $total ккал",
            style = KcalTheme.type.time,
            color = colors.text3,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Расход по факту.
 *
 * Пока данных мало, показывается не пустота и не выдуманное число, а объяснение,
 * чего именно не хватает: человек должен понимать, что приложение не сломано,
 * а честно ждёт двух недель взвешиваний.
 */
@Composable
private fun TdeeCard(
    tdee: TdeeEstimate?,
    goalKcal: Int?,
    onApply: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KcalTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(colors.surface)
            .then(if (tdee != null) Modifier.clickable { onApply(tdee.kcalPerDay) } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (tdee == null) {
            Text(
                "Расход считается по весу и съеденному. Нужно ${TdeeEstimator.MIN_WEIGHT_DAYS}+ дней " +
                    "взвешиваний и записей — тогда здесь появится ваша цифра вместо формулы. " +
                    "Вес пишется из строки ввода: «вес 82,4».",
                style = KcalTheme.type.body,
                color = colors.text2,
            )
            return@Column
        }

        Text(
            "По вашим взвешиваниям расход ≈ ${tdee.kcalPerDay} ккал в день",
            style = KcalTheme.type.bubbleTitle,
            color = colors.text,
        )
        Text(
            buildString {
                append("по ${tdee.days} ${daysWord(tdee.days)} наблюдений, ±${tdee.marginKcal}")
                val diff = goalKcal?.let { it - tdee.kcalPerDay }
                when {
                    diff == null -> Unit
                    diff < 0 -> append(" · цель ниже расхода на ${-diff} ккал в день")
                    diff > 0 -> append(" · цель выше расхода на $diff ккал в день")
                    else -> append(" · цель равна расходу")
                }
                append(" · тап подставит в калории")
            },
            style = KcalTheme.type.time,
            color = colors.text3,
            modifier = Modifier.padding(top = 6.dp),
        )
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
                    // Клавиатура сама не вылезает: вес чаще правят кнопками ±10,
                    // и накрывать ими же половину шита было бы издевательством.
                    focused = false,
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
                        mealName(candidate),
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
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
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
    focused: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
) {
    val colors = KcalTheme.colors
    val requester = remember { FocusRequester() }

    // Курсор встаёт сам ровно в то поле, по которому человек ткнул. Иначе шит
    // открывается с клавиатурой, но без места, куда печатать.
    LaunchedEffect(focused) {
        if (focused) requester.requestFocus()
    }

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
            modifier = Modifier.fillMaxWidth().focusRequester(requester),
        )
        if (value.isEmpty()) {
            Text(placeholder, style = style, color = colors.text3)
        }
    }
}

private fun percent(part: Int, total: Int): Int =
    if (total <= 0) 0 else ((part * 100.0) / total).toInt()

private const val KCAL_PER_PROT = 4
private const val KCAL_PER_FAT = 9
private const val KCAL_PER_CARB = 4

private const val GRAMS_STEP = 10
private const val MIN_GRAMS = 1
private const val MAX_DIGITS = 5
