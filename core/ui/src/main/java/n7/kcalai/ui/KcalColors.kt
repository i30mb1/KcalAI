package n7.kcalai.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Три цвета одного макроса.
 *
 * @param fill заливка бара — единственное место, где цвет несёт величину
 * @param tile фон плитки в шапке
 * @param text метка и знаменатель на плитке, подпись в пузырьке
 * @param onBubble тот же макрос на тёмном пузырьке: [text] там нечитаем
 */
@Immutable
data class MacroColors(
    val fill: Color,
    val tile: Color,
    val text: Color,
    val onBubble: Color,
)

/**
 * Палитра экрана.
 *
 * Своя, а не Material You, и это осознанный отказ: цвета обоев дают бесплатную
 * «родность» ценой лица приложения, а здесь фон, пузырьки и три макроса завязаны
 * друг на друга — подмена любого из них ломает читаемость остальных.
 *
 * Роли названы по месту, а не по тону: `surface` — это входящий пузырёк и чипс
 * композера, `chip` — чипс на этой поверхности. Тёмная тема переворачивает
 * пузырёк (`bubble` становится светлым), поэтому «тёмный» и «светлый»
 * в именах не годятся вовсе.
 */
@Immutable
data class KcalColors(
    val bg: Color,
    val surface: Color,
    val chip: Color,
    val line: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val bubble: Color,
    val onBubble: Color,
    val onBubble2: Color,
    val chipOnBubble: Color,
    val error: Color,
    val protein: MacroColors,
    val fat: MacroColors,
    val carb: MacroColors,
    val isDark: Boolean,
)

val LightKcalColors = KcalColors(
    bg = Color(0xFFF4EFE4),
    surface = Color(0xFFFDFBF5),
    chip = Color(0xFFF0E9D9),
    line = Color(0xFFE5DCC9),
    text = Color(0xFF2C2618),
    text2 = Color(0xFF6B6250),
    text3 = Color(0xFF8D8370),
    bubble = Color(0xFF2C2618),
    onBubble = Color(0xFFF4EFE4),
    onBubble2 = Color(0xFFF4EFE4).copy(alpha = 0.62f),
    chipOnBubble = Color(0xFFF4EFE4).copy(alpha = 0.16f),
    error = Color(0xFFB3261E),
    protein = MacroColors(
        fill = Color(0xFFC2456F),
        tile = Color(0xFFF3DCE2),
        text = Color(0xFF8E2F4E),
        onBubble = Color(0xFFF09BB6),
    ),
    fat = MacroColors(
        fill = Color(0xFFC8862B),
        tile = Color(0xFFF6E5C6),
        text = Color(0xFF7A5008),
        onBubble = Color(0xFFF0C077),
    ),
    carb = MacroColors(
        fill = Color(0xFF4C8F5A),
        tile = Color(0xFFDDEBD8),
        text = Color(0xFF2F5E36),
        onBubble = Color(0xFF93CCA0),
    ),
    isDark = false,
)

val DarkKcalColors = KcalColors(
    bg = Color(0xFF1C1914),
    surface = Color(0xFF26221B),
    chip = Color(0xFF332D23),
    line = Color(0xFF3A3428),
    text = Color(0xFFF4EFE4),
    text2 = Color(0xFFB5AB96),
    text3 = Color(0xFF8D8370),
    bubble = Color(0xFFF4EFE4),
    onBubble = Color(0xFF1C1914),
    onBubble2 = Color(0xFF1C1914).copy(alpha = 0.60f),
    chipOnBubble = Color(0xFF1C1914).copy(alpha = 0.14f),
    error = Color(0xFFF2B8B5),
    protein = MacroColors(
        fill = Color(0xFFE0648C),
        tile = Color(0xFF3A2530),
        text = Color(0xFFF09BB6),
        onBubble = Color(0xFF8E2F4E),
    ),
    fat = MacroColors(
        fill = Color(0xFFD9962F),
        tile = Color(0xFF3A2F1B),
        text = Color(0xFFF0C077),
        onBubble = Color(0xFF7A5008),
    ),
    carb = MacroColors(
        fill = Color(0xFF5FA56E),
        tile = Color(0xFF22331F),
        text = Color(0xFF93CCA0),
        onBubble = Color(0xFF2F5E36),
    ),
    isDark = true,
)
