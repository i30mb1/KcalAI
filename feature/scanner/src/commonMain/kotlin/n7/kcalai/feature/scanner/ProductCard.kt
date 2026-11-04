package n7.kcalai.feature.scanner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.Nutriments
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.ChipNumbers
import n7.kcalai.ui.KcalChip
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.Macro
import n7.kcalai.ui.MiniBar
import n7.kcalai.ui.WideButton
import n7.kcalai.ui.colors

/**
 * Карточка нового продукта под камерой.
 *
 * Сверху — плитка названия, под ней четыре окошка чисел в порядке строк этикетки.
 * Пустое окошко — то, что осталось: в нём ползёт полоска голосов камеры.
 * Заполненное окрашено цветом своего макроса. Тап по окошку — клавиатура телефона,
 * только цифры; тап по кадру или «назад» — она уходит, и снова читает камера.
 *
 * Кнопка сохранения появляется, только когда всё заполнено и сходится, — она и
 * есть «проверь и подтверди». Сама карточка не сохраняет ничего: заполнилось всё
 * с камеры — человек всё равно смотрит и жмёт сам.
 */
@Composable
internal fun ProductCard(
    state: ScanState,
    hint: String?,
    onSave: (Nutriments) -> Unit,
    /** Тап по похожему из справочника — взять его вместо того, чтобы заводить продукт. */
    onPickMatch: (FoodCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KcalTheme.colors
    val check = state.check
    val typing = state.typing

    // Набирать больше нечего: фокус уходит, и клавиатура вместе с ним.
    val focus = LocalFocusManager.current
    LaunchedEffect(typing) {
        if (typing == null) focus.clearFocus()
    }

    ScanPanel(modifier) {
        // Содержимое сжимается и прокручивается, а кнопка стоит на месте:
        // она под пальцем, и уезжать из-под него ей нельзя.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            // Клавиатура забирает полэкрана, и над ней остаётся только то, что сейчас
            // вписывают: иначе вдвоём они закрывают окно целиком.
            NameTile(state)
            if (typing == null || typing === state.name) NameChoices(state)
            if (typing == null || typing === state.barcode) BarcodeLine(state)
            Spacer(Modifier.height(12.dp))
            FieldCards(state)
            if (typing == null || typing === state.name) {
                state.match?.let { MatchChoice(it, onPick = { onPickMatch(it) }) }
            }

            // Ошибка прячет кнопку, замечание — нет. Расходящиеся цифры бывают
            // напечатаны на реальной упаковке, и спорить с упаковкой мы не вправе.
            check?.error?.let { Note(it, colors.error) }
            state.warning?.let { Note(it, colors.text2) }
            if (state.barcode.filled && state.gtin == null) {
                Note("Код не сходится по контрольной цифре — проверьте цифры", colors.error)
            }
            if (typing == null) hint?.let { Note(it, colors.text3) }
        }

        AnimatedVisibility(
            visible = state.canSave,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            WideButton(
                text = state.saveTitle,
                enabled = true,
                onClick = { state.nutriments?.let(onSave) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }

    }
}

/**
 * Поле карточки — настоящее, с клавиатурой телефона.
 *
 * Фокус ходит за [ScanState.typing] в обе стороны: тап ставит поле набираемым, а
 * просьба вписать руками и «дальше» на клавиатуре приводят фокус сюда сами.
 *
 * @param placeholder что видно в пустом поле
 */
@Composable
private fun FieldInput(
    state: ScanState,
    field: ScanField,
    style: TextStyle,
    cursor: Color,
    placeholder: String,
    placeholderStyle: TextStyle,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    val requester = remember { FocusRequester() }
    val typing = state.typing === field
    var edit by remember(field) { mutableStateOf(TextFieldValue(field.text)) }
    // Позже тапа, поставившего курсор: иначе он перебил бы выделение.
    LaunchedEffect(typing) {
        if (typing) {
            requester.requestFocus()
            edit = TextFieldValue(field.text, field.focusSelection)
        }
    }

    BasicTextField(
        // Текст меняет не только клавиатура: пишет камера, нейросеть, чипс с названием.
        value = if (edit.text == field.text) edit else TextFieldValue(field.text, TextRange(field.text.length)),
        onValueChange = { next ->
            state.input(field, next.text)
            // Лишний знак отброшен — курсор остаётся в конце, а не посреди числа.
            edit = if (field.text == next.text) next else TextFieldValue(field.text, TextRange(field.text.length))
        },
        textStyle = style,
        cursorBrush = SolidColor(cursor),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = state.numberKind(field)?.keyboard ?: KeyboardType.Text,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = { state.typeNext() },
            onDone = { state.stopTyping() },
        ),
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) state.startTyping(field) },
        decorationBox = { inner ->
            Box {
                if (field.text.isEmpty()) Text(placeholder, style = placeholderStyle, maxLines = 1)
                inner()
            }
        },
    )
}

/**
 * Название — то, что камера угадывает, а человек знает. Плитка та же, что у чисел:
 * голая строка над ними читалась заголовком, и вписать своё никому не приходило в голову.
 */
@Composable
private fun NameTile(state: ScanState) {
    val colors = KcalTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val typing = state.typing === state.name
    val shape = RoundedCornerShape(KcalShapes.tile)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (typing) colors.surface else colors.chip)
            .then(if (typing) Modifier.border(2.dp, colors.text, shape) else Modifier)
            .clickable {
                state.startTyping(state.name)
                keyboard?.show()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("НАЗВАНИЕ", style = KcalTheme.type.capsTile, color = colors.text2, maxLines = 1)
        FieldInput(
            state = state,
            field = state.name,
            style = KcalTheme.type.input.copy(color = colors.text),
            cursor = colors.text,
            placeholder = "Впишите название",
            placeholderStyle = KcalTheme.type.input.copy(color = colors.text3),
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.Sentences,
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        )
    }
}

/** Варианты названия, набравшие голоса кадров. Тап подставляет в поле. */
@Composable
private fun NameChoices(state: ScanState) {
    val choices = state.nameChoices.filter { it != state.name.text }
    if (choices.isEmpty()) return

    LazyRow(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(choices.size) { index ->
            val choice = choices[index]
            KcalChip(onClick = { state.name.type(choice) }, background = KcalTheme.colors.chip) {
                Text(
                    choice,
                    style = KcalTheme.type.chip,
                    color = KcalTheme.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Штрихкод — строкой под названием. Необязателен: продукт без кода находит
 * только его автор, но завести его можно.
 */
@Composable
private fun BarcodeLine(state: ScanState) {
    val colors = KcalTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val typing = state.typing === state.barcode

    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                state.startTyping(state.barcode)
                keyboard?.show()
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsLabel("штрихкод", color = colors.text3)
        Spacer(Modifier.size(8.dp))
        FieldInput(
            state = state,
            field = state.barcode,
            style = KcalTheme.type.number.copy(fontSize = 13.sp, color = colors.text2),
            cursor = colors.text,
            placeholder = if (typing) "" else "необязательно — нажмите, чтобы вписать",
            placeholderStyle = KcalTheme.type.time.copy(color = colors.text3),
            imeAction = ImeAction.Done,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Четыре окошка в порядке строк этикетки: белки, жиры, углеводы, калории. */
@Composable
private fun FieldCards(state: ScanState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldCard(state, state.prot, "белки", "Б", Macro.PROTEIN, Modifier.weight(1f))
        FieldCard(state, state.fat, "жиры", "Ж", Macro.FAT, Modifier.weight(1f))
        FieldCard(state, state.carb, "углеводы", "У", Macro.CARB, Modifier.weight(1f))
        FieldCard(state, state.kcal, "ккал", "ккал", null, Modifier.weight(1f))
    }
}

/**
 * Окошко одного числа.
 *
 * Пустое — серое, с полоской набранных голосов: значение встаёт, только когда
 * несколько кадров подряд прочитали одно и то же, и видеть надо, **какое**
 * не набирается, — это подсказывает, что подвинуть. Заполненное — цвета своего
 * макроса. Набираемое — с рамкой.
 *
 * @param macro `null` — калории: своего цвета у них нет, они складываются из всех трёх
 */
@Composable
private fun FieldCard(
    state: ScanState,
    field: ScanField,
    label: String,
    key: String,
    macro: Macro?,
    modifier: Modifier,
) {
    val colors = KcalTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val tint = macro?.colors()
    val typing = state.typing === field
    val missing = state.isMissing(field)
    val accent = tint?.fill ?: colors.text
    val shape = RoundedCornerShape(KcalShapes.tile)

    val background by animateColorAsState(
        when {
            typing -> colors.surface
            !missing -> tint?.tile ?: colors.chip
            else -> colors.chip
        },
        tween(FILL_MS),
    )
    val progress by animateFloatAsState(if (missing) state.progress(key) else 1f, tween(FILL_MS))

    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .then(if (typing) Modifier.border(2.dp, accent, shape) else Modifier)
            .clickable {
                state.startTyping(field)
                keyboard?.show()
            }
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(
            label.uppercase(),
            style = KcalTheme.type.capsTile,
            color = tint?.text ?: colors.text2,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.padding(top = 3.dp).height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = KcalTheme.type.number.copy(fontSize = 17.sp)
            FieldInput(
                state = state,
                field = field,
                style = style.copy(color = if (missing && !typing) colors.text3 else colors.text),
                cursor = accent,
                placeholder = if (typing) "" else "—",
                placeholderStyle = style.copy(color = colors.text3),
                imeAction = ImeAction.Next,
                modifier = Modifier.width(IntrinsicSize.Min),
            )
            if (macro != null && field.text.isNotEmpty()) {
                Text(" г", style = KcalTheme.type.time, color = colors.text3)
            }
        }
        MiniBar(
            fraction = progress,
            fill = if (missing) accent.copy(alpha = 0.55f) else accent,
            height = 3.dp,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/**
 * Похожий продукт из справочника — под числами, потому что он их альтернатива:
 * таблицу не прочесть, а «Творог 5%» в справочнике есть. Подписан своими
 * калориями, чтобы человек сверил их с пачкой, прежде чем взять.
 */
@Composable
private fun MatchChoice(candidate: FoodCandidate, onPick: () -> Unit) {
    val colors = KcalTheme.colors
    Column(Modifier.padding(top = 12.dp)) {
        CapsLabel("похоже на")
        KcalChip(onClick = onPick, background = colors.chip, modifier = Modifier.padding(top = 6.dp)) {
            Text(
                candidate.displayName,
                style = KcalTheme.type.chip,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            ChipNumbers("· ${candidate.nutriments.kcal100} ккал на 100 г", colors.text3)
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(
        text,
        style = KcalTheme.type.body,
        color = color,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Заполнение окошка: за глазом успевает, за кадром — тоже. */
private const val FILL_MS = 280
