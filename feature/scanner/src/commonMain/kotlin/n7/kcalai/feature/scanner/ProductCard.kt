package n7.kcalai.feature.scanner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import n7.kcalai.model.Nutriments
import n7.kcalai.ui.CapsLabel
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
 * Сверху — название с пачки, под ним четыре окошка чисел в порядке строк этикетки.
 * Пустое окошко — то, что осталось: в нём ползёт полоска голосов камеры.
 * Заполненное окрашено цветом своего макроса. Тап по окошку — под ним цифры,
 * тап по нему же ещё раз — цифры уходят, и снова читает камера.
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
    modifier: Modifier = Modifier,
) {
    val colors = KcalTheme.colors
    val check = state.check
    val pad = state.typing?.let(state::padNumber)

    // Название набирают клавиатурой. Ушли из него к цифрам — клавиатура прячется.
    val focus = LocalFocusManager.current
    LaunchedEffect(state.typing) {
        if (state.typing !== state.name) focus.clearFocus()
    }

    ScanPanel(modifier) {
        // Содержимое сжимается и прокручивается, а кнопка и цифры стоят на месте:
        // они под пальцем, и уезжать из-под него им нельзя.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            NameField(state.name, onFocused = { state.startTyping(state.name) })
            NameChoices(state)
            BarcodeLine(state)
            Spacer(Modifier.height(12.dp))
            FieldCards(state)

            // Ошибка прячет кнопку, замечание — нет. Расходящиеся цифры бывают
            // напечатаны на реальной упаковке, и спорить с упаковкой мы не вправе.
            check?.error?.let { Note(it, colors.error) }
            state.warning?.let { Note(it, colors.text2) }
            if (state.barcode.filled && state.gtin == null) {
                Note("Код не сходится по контрольной цифре — проверьте цифры", colors.error)
            }
            hint?.let { Note(it, colors.text3) }
        }

        AnimatedVisibility(
            visible = state.canSave,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            WideButton(
                text = "Всё верно — сохранить",
                enabled = true,
                onClick = { state.nutriments?.let(onSave) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }

        // Анимируется только появление и уход цифр. Переход с граммов на калории —
        // та же панель без запятой, и мигать ей незачем.
        AnimatedContent(
            targetState = pad,
            contentKey = { it != null },
            transitionSpec = {
                (expandVertically() + fadeIn()) togetherWith (shrinkVertically() + fadeOut()) using
                    SizeTransform(clip = false)
            },
            label = "pad",
        ) { number ->
            if (number != null) {
                Column {
                    if (number != PadNumber.CODE) ScannedNumbers(state)
                    DigitPad(
                        number = number,
                        onKey = state::press,
                        onClear = state::clearTyping,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            } else {
                Spacer(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Название — то единственное, что камера угадывает, а человек знает. Его набирают клавиатурой. */
@Composable
private fun NameField(field: ScanField, onFocused: () -> Unit) {
    val colors = KcalTheme.colors
    val focus = LocalFocusManager.current

    Box {
        BasicTextField(
            value = field.text,
            onValueChange = field::type,
            textStyle = KcalTheme.type.title.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFocused() },
        )
        if (field.text.isEmpty()) {
            Text("Название с пачки", style = KcalTheme.type.title, color = colors.text3)
        }
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
 * только его автор, но завести его можно. Тап — те же цифры, что у чисел.
 */
@Composable
private fun BarcodeLine(state: ScanState) {
    val colors = KcalTheme.colors
    val typing = state.typing === state.barcode
    val code = state.barcode.text

    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { if (typing) state.stopTyping() else state.startTyping(state.barcode) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapsLabel("штрихкод", color = colors.text3)
        Spacer(Modifier.size(8.dp))
        if (code.isNotEmpty()) {
            Text(code, style = KcalTheme.type.number.copy(fontSize = 13.sp), color = colors.text2)
        } else if (!typing) {
            Text("необязательно — нажмите, чтобы вписать", style = KcalTheme.type.time, color = colors.text3)
        }
        if (typing) Caret(colors.text)
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
 * макроса. Набираемое — с рамкой и кареткой.
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
            .clickable { if (typing) state.stopTyping() else state.startTyping(field) }
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
            val shown = field.text.ifEmpty { if (typing) "" else "—" }
            Text(
                shown,
                style = KcalTheme.type.number.copy(fontSize = 17.sp),
                color = if (missing && !typing) colors.text3 else colors.text,
                maxLines = 1,
            )
            if (typing) Caret(accent)
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
 * Каретка набираемого поля. Не мигает: мигание — бесконечная анимация, а экран
 * съёмки и так тратит батарею на камеру. Где набирают, показывает рамка окошка.
 */
@Composable
private fun Caret(color: Color) {
    Box(
        Modifier
            .padding(start = 2.dp)
            .size(width = 2.dp, height = 18.dp)
            .background(color)
    )
}

/**
 * Числа, которые распознались, но которые разбор не разложил сам.
 *
 * Так выглядит честный отказ: приложение не угадывает, куда поставить цифру,
 * а показывает всё, что увидело, — тап по чипсу ставит число в набираемое поле.
 */
@Composable
private fun ScannedNumbers(state: ScanState) {
    val numbers = state.numbers.distinct()
    if (numbers.isEmpty()) return

    Column(Modifier.padding(top = 10.dp)) {
        CapsLabel("распознано с этикетки")
        LazyRow(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(numbers.size) { index ->
                val value = numbers[index]
                KcalChip(onClick = { state.put(value) }, background = KcalTheme.colors.chip) {
                    Text(value, style = KcalTheme.type.chip, color = KcalTheme.colors.text)
                }
            }
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
