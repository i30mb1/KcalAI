package n7.kcalai.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Умеет ли система размывать то, что лежит под элементом.
 *
 * На Android — с 17 QPR2 (SDK 37.2), где появился `RenderNode.setBackdropRenderEffect`.
 * Раньше и на iOS — нет: тогда экран рисует плотный фон вместо стекла.
 */
expect val frostSupported: Boolean

/**
 * Матовое стекло: под содержимым элемента — размытое и насыщенное то, что лежит
 * за ним. Где [frostSupported] ложно, модификатор ничего не делает, и фон
 * подставляет сам экран.
 *
 * @param saturation 1 — как есть; больше — цвет реплик под стеклом просвечивает,
 *        а не сереет от размытия
 */
expect fun Modifier.frost(blur: Dp, saturation: Float): Modifier
