package n7.kcalai.feature.scanner

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.MiniBar
import n7.kcalai.ui.WideButton

/**
 * Окно видоискателя.
 *
 * В распознавании не участвует — движок смотрит весь кадр. Нужно человеку:
 * без него непонятно, куда наводить, и съёмка превращается в угадывание.
 *
 * Затемнение рисуется одним слоем с вырезанным отверстием, а не четырьмя
 * прямоугольниками вокруг: у скруглённого окна четыре прямоугольника оставляют
 * незатемнённые уголки, и видно это сразу.
 */
@Composable
fun Viewfinder(modifier: Modifier = Modifier, windowHeight: Float = 0.42f) {

    Canvas(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val inset = 24.dp.toPx()
        val height = size.height * windowHeight
        // Окно стоит выше геометрического центра: снизу его подпирает панель
        // разбора, и по оптическому центру оно уезжает ей под край.
        val top = (size.height - height) * 0.42f
        val corner = CornerRadius(22.dp.toPx())
        val topLeft = Offset(inset, top)
        val window = Size(size.width - inset * 2, height)

        drawRect(SCRIM)
        drawRoundRect(Color.Transparent, topLeft, window, corner, blendMode = BlendMode.Clear)
        drawRoundRect(
            color = ON_CAMERA.copy(alpha = 0.85f),
            topLeft = topLeft,
            size = window,
            cornerRadius = corner,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/** Затемнение вокруг окна. Одно на обе темы: за ним живая картинка, а не наш фон. */
private val SCRIM = Color(0xFF1C1914).copy(alpha = 0.44f)

/**
 * Чернила поверх камеры.
 *
 * Не берутся из темы намеренно: под ними не фон приложения, а кадр, и в тёмной
 * теме `bg` оказался бы тёмным на тёмном. Всё, что лежит на превью, светлое
 * всегда — как и затемнение под ним.
 */
private val ON_CAMERA = Color(0xFFF4EFE4)

/**
 * Строка поверх камеры: что за экран слева, что происходит справа.
 *
 * Счётчик кадров — не отладка. Съёмка идёт секундами, и без него неподвижный
 * экран неотличим от зависшего.
 */
@Composable
fun ScanTopBar(
    title: String,
    meta: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Закрыть съёмку",
                tint = ON_CAMERA,
                modifier = Modifier.size(20.dp),
            )
        }

        CapsLabel(title, color = ON_CAMERA.copy(alpha = 0.8f))
        Spacer(Modifier.weight(1f))

        if (meta != null) {
            Text(
                meta,
                style = KcalTheme.type.time,
                color = ON_CAMERA.copy(alpha = 0.7f),
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        action?.invoke()
    }
}

/** Подсказка поверх камеры. Пилюля, а не текст по фону: за ней живая картинка. */
@Composable
fun HintPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = KcalTheme.type.label,
        color = KcalTheme.colors.text,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(KcalTheme.colors.surface.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Нижняя панель разбора: та же поверхность и тот же радиус, что у шитов. */
@Composable
fun ScanPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = KcalShapes.sheet, topEnd = KcalShapes.sheet))
            .background(KcalTheme.colors.surface)
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 10.dp),
    ) {
        content()
    }
}

/**
 * Кольцо общего прогресса.
 *
 * Одно число вместо шести полосок — ответ на «долго ли ещё». Полоски отвечают
 * на другой вопрос, «на чём именно застряло», и одно другое не заменяет.
 */
@Composable
fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val colors = KcalTheme.colors
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(FILL_MS),
        label = "ring",
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = colors.line,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke),
            )
            drawArc(
                color = colors.bubble,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke),
            )
        }
        Text(
            "${(animated * 100).toInt()}%",
            style = KcalTheme.type.number,
            color = colors.text,
        )
    }
}

/**
 * Одно поле разбора: подпись, полоска набранной уверенности, значение.
 *
 * Полоска показывает не «идёт загрузка», а согласие кадров: значение встаёт
 * в поле, только когда несколько кадров подряд прочитали одно и то же. Видеть
 * надо именно **какое** поле не набралось — это подсказывает действие: подвинуть
 * камеру, убрать блик с той самой строки, поднести ближе.
 *
 * Значение при этом всегда можно набрать самому, поверх распознанного. Съёмка —
 * способ не печатать пять чисел с мобильной клавиатуры, а не обязанность ждать,
 * пока камера прочитает то, что человек видит на пачке своими глазами.
 *
 * @param onClear крестик: снять набранное значение. Распознавание после этого
 *        не подставит его заново — снятое значение оно больше не предлагает,
 *        иначе крестик не делал бы ничего.
 */
@Composable
fun ScanEditRow(
    label: String,
    value: String,
    placeholder: String,
    fraction: Float,
    color: Color,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    optional: Boolean = false,
    decimal: Boolean = false,
    unit: String? = null,
) {
    val colors = KcalTheme.colors
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(FILL_MS),
        label = label,
    )
    val done = fraction >= 1f

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(74.dp)) {
            CapsLabel(label, color = if (optional) colors.text3 else colors.text2)
            // «Необязательное» сказано словом, а не одной бледностью: бледное
            // читается как «недоступно», и человек ждёт, пока оно заполнится.
            if (optional) {
                Text(
                    "необяз.",
                    style = KcalTheme.type.time,
                    color = colors.text3,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        MiniBar(
            fraction = animated,
            fill = if (done) color else color.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            height = if (optional) 4.dp else 6.dp,
        )

        Row(
            modifier = Modifier.width(104.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = KcalTheme.type.number.copy(
                        color = colors.text,
                        textAlign = TextAlign.End,
                    ),
                    cursorBrush = SolidColor(colors.text),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onFocus(it.isFocused) },
                )
                if (value.isEmpty()) {
                    // На месте пустого значения стоит процент набранного, а не
                    // прочерк: прочерк читается как «не будет», а полоска в это
                    // время как раз ползёт.
                    Text(
                        placeholder,
                        style = KcalTheme.type.time,
                        color = colors.text3,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (unit != null && value.isNotEmpty()) {
                Text(
                    unit,
                    style = KcalTheme.type.time,
                    color = colors.text3,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }

            ClearButton(visible = value.isNotEmpty(), onClick = onClear)
        }
    }
}

/**
 * Крестик, снимающий уже набранное значение.
 *
 * Место под него занято всегда, даже когда значения нет: иначе строка дёргается
 * по ширине ровно в тот момент, когда камера что-то дочитала, — и палец промахивается
 * мимо соседнего поля.
 */
@Composable
private fun ClearButton(visible: Boolean, onClick: () -> Unit) {
    val colors = KcalTheme.colors

    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .then(if (visible) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Стереть значение",
                tint = colors.text3,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Действия панели: подтвердить разбор или уйти заполнять руками. */
@Composable
fun ScanActions(
    primary: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        WideButton(
            text = primary,
            enabled = primaryEnabled,
            onClick = onPrimary,
            modifier = Modifier.weight(1f),
        )
        // Ручной ввод доступен всегда: распознавание может не сойтись вовсе,
        // и тупик на этом месте означал бы, что продукт не завести никак.
        Box(
            modifier = Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(KcalShapes.tile))
                .background(KcalTheme.colors.chip)
                .clickable(onClick = onSecondary)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(secondary, style = KcalTheme.type.input, color = KcalTheme.colors.text)
        }
    }
}

/** Заполнение полоски: за глазом успевает, за кадром — тоже. */
private const val FILL_MS = 280
