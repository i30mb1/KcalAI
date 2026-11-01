package n7.kcalai.feature.scanner

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
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
 *
 * Положение окна задаёт экран съёмки: на шаге кода это узкая полоса посередине,
 * в карточке — окно под таблицу между персонажами и панелью, и между ними окно
 * перетекает плавно, вместе с камерой.
 *
 * @param top верх окна от верха экрана
 * @param height высота окна
 * @param caught код пойман — рамка зеленеет, пока ищется товар
 */
@Composable
fun Viewfinder(top: Dp, height: Dp, modifier: Modifier = Modifier, caught: Boolean = false) {
    val frame by animateColorAsState(
        if (caught) CAUGHT else ON_CAMERA.copy(alpha = 0.85f),
        tween(FRAME_MS),
        label = "frame",
    )

    Canvas(
        modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val inset = 24.dp.toPx()
        val corner = CornerRadius(22.dp.toPx())
        val topLeft = Offset(inset, top.toPx())
        val window = Size(size.width - inset * 2, height.toPx().coerceAtLeast(0f))

        drawRect(SCRIM)
        drawRoundRect(Color.Transparent, topLeft, window, corner, blendMode = BlendMode.Clear)
        drawRoundRect(
            color = frame,
            topLeft = topLeft,
            size = window,
            cornerRadius = corner,
            style = Stroke(width = (if (caught) 2.5.dp else 1.5.dp).toPx()),
        )
    }
}

/** Рамка пойманного кода: зелёный углеводов на тёмном — виден на любой пачке. */
private val CAUGHT = Color(0xFF93CCA0)

private const val FRAME_MS = 200

/**
 * Подложка под камерой — пока она не отдала первый кадр.
 *
 * Тёмная, как выключенная камера, а не фон приложения: окно видоискателя
 * вырезает затемнение до подложки, и на светлом фоне первые полсекунды в рамке
 * стоял белый прямоугольник — экран вспыхивал ровно при открытии.
 */
val CAMERA_BACKDROP = Color(0xFF1C1914)

/** Затемнение вокруг окна. Одно на обе темы: за ним живая картинка, а не наш фон. */
private val SCRIM = CAMERA_BACKDROP.copy(alpha = 0.44f)

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
 * [meta] — счётчик кадров для разработчика. Человеку о том, что съёмка идёт,
 * говорят персонажи над камерой и подсказка в карточке, см. [panelHint].
 *
 * @param onCamera под строкой живое превью. Без камеры — ручной ввод кода,
 *        отказ в доступе — под ней фон темы, и светлые «чернила камеры»
 *        на светлом фоне не видны: крестик «закрыть» просто пропадал.
 */
@Composable
fun ScanTopBar(
    title: String,
    meta: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCamera: Boolean = true,
    action: (@Composable () -> Unit)? = null,
) {
    val ink = if (onCamera) ON_CAMERA else KcalTheme.colors.text

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
                tint = ink,
                modifier = Modifier.size(20.dp),
            )
        }

        CapsLabel(title, color = ink.copy(alpha = 0.8f))
        Spacer(Modifier.weight(1f))

        if (meta != null) {
            Text(
                meta,
                style = KcalTheme.type.time,
                color = ink.copy(alpha = 0.7f),
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        action?.invoke()
    }
}

/**
 * Вопрос перед тем, как закрыть заполненную карточку.
 *
 * Закрыть — одно касание «назад» или крестик, а заполнить — минута с пачкой
 * в руке. Без вопроса жест «назад», которым прятали клавиатуру, уносил
 * карточку целиком, когда клавиатура уже была спрятана.
 */
@Composable
fun DiscardDraftDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    val colors = KcalTheme.colors

    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Закрыть без сохранения?", style = KcalTheme.type.title) },
        text = { Text("Всё, что набрано на карточке, пропадёт.", style = KcalTheme.type.body) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text("Закрыть", style = KcalTheme.type.input, color = colors.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text("Остаться", style = KcalTheme.type.input, color = colors.text)
            }
        },
        containerColor = colors.bg,
        titleContentColor = colors.text,
        textContentColor = colors.text2,
    )
}

/**
 * Подсказка поверх камеры. Пилюля, а не текст по фону: за ней живая картинка.
 *
 * @param busy идёт поиск — рядом с текстом крутится индикатор. Крутится он,
 *        только пока ищется товар: это секунды, а не всё время съёмки.
 */
@Composable
fun HintPill(text: String, modifier: Modifier = Modifier, busy: Boolean = false) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(KcalTheme.colors.surface.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp).size(12.dp),
                color = KcalTheme.colors.text2,
                strokeWidth = 1.5.dp,
            )
        }
        Text(
            text,
            style = KcalTheme.type.label,
            color = KcalTheme.colors.text,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Нижняя панель разбора: та же поверхность и тот же радиус, что у шитов.
 *
 * Отступы под полосу навигации и клавиатуру — внутри фона, а не снаружи:
 * экран идёт до края, и панель обязана дойти до него сама, иначе под
 * жестовой полоской остаётся полоса фона другого цвета.
 */
@Composable
fun ScanPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = KcalShapes.sheet, topEnd = KcalShapes.sheet))
            .background(KcalTheme.colors.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
    ) {
        content()
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
