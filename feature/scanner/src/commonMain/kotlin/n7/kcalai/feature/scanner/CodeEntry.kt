package n7.kcalai.feature.scanner

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import n7.kcalai.repositories.BarcodeValidator
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.WideButton

/**
 * Цифры под штрих-кодом — когда камера код не берёт или камеры нет.
 *
 * Смазанный код на мятой пачке не читается никогда, и цифры под ним — рабочий
 * путь, а не аварийный. Клавиатура телефона открывается сразу и только с цифрами.
 * Камера за панелью продолжает искать код — поймает раньше, чем человек допечатает,
 * тем лучше.
 *
 * «Найти» включается только на коде с верной контрольной цифрой: опечатка
 * в одной цифре увела бы человека заводить продукт под несуществующим номером,
 * и найти его потом не смог бы никто, включая его самого.
 *
 * @param searching код отправлен, товар ищется — кнопки ждут ответа
 * @param onNoCode кода на пачке нет вовсе. Без камеры панель — единственное,
 *        что видно, и дверь к карточке без кода обязана быть и здесь.
 */
@Composable
internal fun CodeEntry(
    title: String,
    searching: Boolean,
    onSubmit: (String) -> Unit,
    onNoCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KcalTheme.colors
    var code by rememberSaveable { mutableStateOf("") }
    val normalized = BarcodeValidator.normalize(code)
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }

    ScanPanel(modifier) {
        Text(title, style = KcalTheme.type.title, color = colors.text)

        BasicTextField(
            value = code,
            onValueChange = { code = NumberKind.CODE.clean(it) },
            textStyle = KcalTheme.type.hero.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = NumberKind.CODE.keyboard,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (!searching) normalized?.let(onSubmit) }),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).focusRequester(requester),
        )
        Text(
            // Молчать о том, почему кнопка серая, нельзя: человек решит, что сломано.
            when {
                code.isEmpty() -> "8, 12 или 13 цифр"
                normalized == null -> "Код не сходится — проверьте цифры"
                else -> "Код верный"
            },
            style = KcalTheme.type.time,
            color = if (code.isNotEmpty() && normalized == null) colors.error else colors.text3,
            modifier = Modifier.padding(top = 4.dp),
        )

        WideButton(
            text = if (searching) "Ищу товар…" else "Найти",
            enabled = normalized != null && !searching,
            onClick = { normalized?.let(onSubmit) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        WideButton(
            text = NO_BARCODE,
            enabled = !searching,
            onClick = onNoCode,
            filled = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** У развесного и домашнего кода нет — дальше карточка без него. */
internal const val NO_BARCODE = "Нет штрих-кода"
