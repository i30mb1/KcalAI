package n7.kcalai.feature.diary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import n7.kcalai.model.FoodCandidate
import n7.kcalai.resolver.ResolvedItem
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.ChipNumbers
import n7.kcalai.ui.KcalChip
import n7.kcalai.ui.MacroDot
import n7.kcalai.ui.PopSpring
import n7.kcalai.ui.popIn
import n7.kcalai.ui.colors
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme

/**
 * Композер: строка ввода и ряд подсказок над ней.
 *
 * Кнопки отправки нет — добавляет только тап по чипсу. Это не экономия места:
 * поле принимает свободный текст, и кнопка «добавить» означала бы выбор первого
 * попавшегося совпадения вслепую, ровно то, от чего предложения и защищают.
 *
 * Три способа ввода стоят рядом и равноправны: набрать, отсканировать код, снять
 * этикетку. У каждого свой случай, и прятать любой из них в меню незачем.
 */
@Composable
fun Composer(
    input: String,
    row: ComposerRow,
    onInputChange: (String) -> Unit,
    onPick: (ResolvedItem) -> Unit,
    onPickScanned: (ResolvedItem) -> Unit,
    onPickCandidate: (FoodCandidate) -> Unit,
    onLogWeight: () -> Unit,
    onOpenScan: () -> Unit,
    onOpenLabelScan: () -> Unit,
    onOpenLabelDebug: () -> Unit,
) {
    val colors = KcalTheme.colors
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // После скана в строке уже стоит название товара, и дописать к нему вес
    // человек должен сразу — иначе подстановка экономит ровно ноль движений.
    // Курсор уводится в конец строки: иначе он встаёт перед названием.
    val scannedItem = (row as? ComposerRow.Scanned)?.item?.sourceText
    LaunchedEffect(scannedItem) {
        if (scannedItem != null) {
            requester.requestFocus()
            keyboard?.show()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bg)
            .imePadding()
            .navigationBarsPadding()
            .padding(top = 10.dp, bottom = 10.dp)
    ) {
        HintRow(row, onPick, onPickScanned, onPickCandidate, onLogWeight)

        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(KcalShapes.input))
                .background(colors.surface)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                // Поле держит собственное значение с курсором, а не отражает
                // строку состояния напрямую. Причин две, и обе обязательные.
                //
                // Курсор: подставленное после скана название приходит извне,
                // и без явной позиции он остаётся в нуле — вес дописывался бы
                // перед названием.
                //
                // Рассинхрон: состояние едет через combine и возвращается на кадр
                // позже набранного. Сверяйся поле с ним напрямую — оно на этот кадр
                // откатывало бы последнюю букву. Поэтому сверка идёт с тем, что
                // поле само отправило наружу: расходится только подстановка извне.
                var field by remember { mutableStateOf(TextFieldValue(input)) }
                var sent by remember { mutableStateOf(input) }
                if (input != sent) {
                    field = TextFieldValue(input, TextRange(input.length))
                    sent = input
                }

                BasicTextField(
                    value = field,
                    onValueChange = { value ->
                        field = value
                        if (value.text != sent) {
                            sent = value.text
                            onInputChange(value.text)
                        }
                    },
                    textStyle = KcalTheme.type.input.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.text),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester),
                )
                if (input.isEmpty()) {
                    Text(
                        "Написать, что съели…",
                        style = KcalTheme.type.input,
                        color = colors.text3,
                    )
                }
            }

            // Штрих-код опознаёт товар в упаковке точнее любого названия, и
            // заставлять человека набирать «активиа натуральная 4%» вместо
            // одного наведения камеры незачем.
            TapTarget(onClick = onOpenScan, description = "Сканировать штрих-код") {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = colors.text2,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Долгое нажатие уводит в отладочную съёмку: кнопок в шапке экран
            // больше не носит, а проверять разбор этикетки по-прежнему нужно.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onOpenLabelScan,
                        onLongClick = onOpenLabelDebug,
                    )
                    .semantics { contentDescription = "Снять этикетку" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.bubble),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = colors.onBubble,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Ряд подсказок.
 *
 * Ряд ровно один, и что в нём — решает состояние. Показывать предсказания вместе
 * с выдачей поиска значило бы предлагать человеку выбирать между тем, что он
 * набрал, и тем, что он обычно ест, — выбор, которого он не просил.
 */
@Composable
private fun HintRow(
    row: ComposerRow,
    onPick: (ResolvedItem) -> Unit,
    onPickScanned: (ResolvedItem) -> Unit,
    onPickCandidate: (FoodCandidate) -> Unit,
    onLogWeight: () -> Unit,
) {
    // Ряд разворачивается и сворачивается, а не выскакивает: иначе композер
    // меняет высоту скачком, и лента над ним дёргается на каждую подсказку.
    // На время сворачивания держится последний непустой ряд — сворачиваться
    // должны чипсы, а не пустое место.
    var shown by remember { mutableStateOf(row) }
    if (row !is ComposerRow.None) shown = row

    AnimatedVisibility(
        visible = row !is ComposerRow.None,
        enter = expandVertically(PopSpring.forInt()) + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        HintChips(shown, onPick, onPickScanned, onPickCandidate, onLogWeight)
    }
}

/** Пружина по высоте для expand/shrink: у них свой тип, но характер тот же. */
private fun SpringSpec<Float>.forInt(): SpringSpec<IntSize> =
    spring(dampingRatio = dampingRatio, stiffness = stiffness, visibilityThreshold = IntSize(1, 1))

@Composable
private fun HintChips(
    row: ComposerRow,
    onPick: (ResolvedItem) -> Unit,
    onPickScanned: (ResolvedItem) -> Unit,
    onPickCandidate: (FoodCandidate) -> Unit,
    onLogWeight: () -> Unit,
) {
    val caption = when (row) {
        is ComposerRow.Weight -> "похоже на вес"
        is ComposerRow.Results -> "нашлось"
        is ComposerRow.Scanned ->
            if (row.item.gramsGuessed) "найдено по коду · допишите вес" else "найдено по коду"
        is ComposerRow.Predictions -> "обычно в это время"
        ComposerRow.None -> ""
    }

    Column {
        CapsLabel(caption, Modifier.padding(start = 16.dp, bottom = 8.dp))

        // Ключ — позиция плюс продукт: чипс подпрыгивает, когда на его месте
        // появилось что-то другое, и стоит на месте, пока выдача та же. Один
        // продукт может стоять в ряду дважды («молоко и молоко»), поэтому
        // ключ без позиции падал бы на дубликате.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            when (row) {
                is ComposerRow.Weight -> item(key = "weight") {
                    Chip { WeightChip(row.grams, onLogWeight) }
                }

                is ComposerRow.Results -> items(
                    count = row.items.size,
                    key = { index -> "$index:${row.items[index].candidate?.ref ?: row.items[index].sourceText}" },
                ) { index ->
                    val resolved = row.items[index]
                    Chip { ResolvedChip(resolved) { onPick(resolved) } }
                }

                is ComposerRow.Scanned -> item(key = "scanned:${row.item.candidate?.ref}") {
                    Chip { ResolvedChip(row.item) { onPickScanned(row.item) } }
                }

                is ComposerRow.Predictions -> items(
                    count = row.items.size,
                    key = { index -> "$index:${row.items[index].ref}" },
                ) { index ->
                    val candidate = row.items[index]
                    Chip { PredictionChip(candidate) { onPickCandidate(candidate) } }
                }

                ComposerRow.None -> Unit
            }
        }
    }
}

/** Чипс вырастает из своего левого нижнего угла — оттуда, где стоит курсор. */
@Composable
private fun LazyItemScope.Chip(content: @Composable () -> Unit) {
    Box(Modifier.animateItem(fadeOutSpec = null).popIn(origin = TransformOrigin(0f, 1f))) {
        content()
    }
}

/**
 * Единственная точка ввода веса тела.
 *
 * Отдельного диалога и кнопки в шапке больше нет: поле, в которое человек и так
 * пишет «овсянка 200», прекрасно принимает «вес 82,4», а распознать это дешевле,
 * чем заводить экран с одним числом.
 */
@Composable
private fun WeightChip(grams: Int, onClick: () -> Unit) {
    val colors = KcalTheme.colors

    KcalChip(onClick = onClick, background = colors.bubble) {
        Icon(
            Icons.Default.MonitorWeight,
            contentDescription = null,
            tint = colors.onBubble,
            modifier = Modifier.size(14.dp),
        )
        Text(
            "Записать вес ${formatKg(grams)}",
            style = KcalTheme.type.chip,
            color = colors.onBubble,
            modifier = Modifier.padding(start = 7.dp),
        )
    }
}

/**
 * Предложение поиска или найденное по коду.
 *
 * Нажатие сразу кладёт позицию в дневник: выбор из списка и есть подтверждение,
 * а второй шаг «вы уверены?» на десятой записи за день становится издевательством.
 */
@Composable
private fun ResolvedChip(item: ResolvedItem, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    val candidate = item.candidate

    // Неопознанный сегмент фразы остаётся видимым, но не кликается: тап по нему
    // добавил бы в дневник неизвестно что.
    if (candidate == null) {
        KcalChip(onClick = null, background = colors.chip) {
            Text(item.sourceText, style = KcalTheme.type.chip, color = colors.text3, maxLines = 1)
        }
        return
    }

    KcalChip(onClick = onClick, background = colors.surface) {
        MacroDot(dominantMacro(candidate.nutriments).colors().fill)
        Text(
            candidate.displayName,
            style = KcalTheme.type.chip,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 7.dp),
        )
        ChipNumbers(
            "· ${formatGrams(item.grams)} · ${kcalFor(candidate, item.grams)}",
            colors.text3,
        )
    }
}

/**
 * Идея 1: что человек, скорее всего, съест прямо сейчас.
 *
 * Модель биграмм над личной историей знает, что после овсянки идёт кофе с молоком,
 * и превращает самый частый сценарий трекинга из набора текста в один тап.
 */
@Composable
private fun PredictionChip(candidate: FoodCandidate, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    val grams = candidate.servingG ?: 0

    KcalChip(onClick = onClick, background = colors.surface) {
        MacroDot(dominantMacro(candidate.nutriments).colors().fill)
        Text(
            candidate.displayName,
            style = KcalTheme.type.chip,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 7.dp),
        )
        ChipNumbers("· ${formatGrams(grams)} · ${kcalFor(candidate, grams)}", colors.text3)
    }
}

/** Тап-таргет 44dp вокруг иконки: визуальный размер иконки от этого не меняется. */
@Composable
private fun TapTarget(
    onClick: () -> Unit,
    description: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

private fun kcalFor(candidate: FoodCandidate, grams: Int): Int =
    ((candidate.nutriments.kcal100.toLong() * grams + 50) / 100).toInt()
