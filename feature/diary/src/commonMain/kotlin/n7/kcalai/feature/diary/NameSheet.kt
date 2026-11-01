package n7.kcalai.feature.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.WideButton

/**
 * Имя, которое человек назвал персонажам: без лишних пробелов, хоть с одной буквой.
 *
 * Не длиннее двадцати знаков: имя открывает фразу уведомления, и длинное съело бы
 * всё остальное. Обрезать не стоит — обрезанное имя хуже, чем никакого.
 *
 * @return имя или `null`, если это не имя
 */
fun cleanName(text: String): String? {
    val name = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
    if (name.isEmpty() || name.length > MAX_NAME || name.none(Char::isLetter)) return null
    return name
}

private val WHITESPACE = Regex("\\s+")
private const val MAX_NAME = 20

/**
 * «Как тебя зовут?» — ответ на вопрос Яйца.
 *
 * Клавиатура сразу: человек нажал «Сказать», и ему есть что печатать.
 */
@Composable
fun NameSheet(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = KcalTheme.colors
    var text by remember { mutableStateOf("") }
    val name = cleanName(text)
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }

    Sheet(onDismiss) {
        Text("Как тебя зовут?", style = KcalTheme.type.title, color = colors.text)

        Box(
            Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(KcalShapes.tile))
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            BasicTextField(
                value = text,
                // С запасом над пределом: длинное имя видно целиком, а кнопка просто не нажмётся.
                onValueChange = { text = it.take(MAX_NAME * 2) },
                textStyle = KcalTheme.type.input.copy(color = colors.text),
                cursorBrush = SolidColor(colors.text),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { name?.let(onConfirm) }),
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
            )
            if (text.isEmpty()) {
                Text("Имя", style = KcalTheme.type.input, color = colors.text3)
            }
        }

        WideButton(
            text = "Готово",
            enabled = name != null,
            onClick = { name?.let(onConfirm) },
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
        )
    }
}
