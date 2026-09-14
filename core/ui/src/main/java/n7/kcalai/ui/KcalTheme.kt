package n7.kcalai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LocalKcalColors: ProvidableCompositionLocal<KcalColors> =
    staticCompositionLocalOf { LightKcalColors }

private val LocalKcalTypography: ProvidableCompositionLocal<KcalTypography> =
    staticCompositionLocalOf { KcalTypography() }

/** Точка доступа к палитре и кеглям: `KcalTheme.colors.bubble`, `KcalTheme.type.caps`. */
object KcalTheme {
    val colors: KcalColors
        @Composable @ReadOnlyComposable get() = LocalKcalColors.current

    val type: KcalTypography
        @Composable @ReadOnlyComposable get() = LocalKcalTypography.current
}

/**
 * Тема приложения.
 *
 * Помимо своих токенов заполняется и [MaterialTheme.colorScheme] — не для порядка:
 * шиты, текстовые поля и курсор рисует Material своими роля́ми, и оставленная
 * по умолчанию схема выдала бы на нашем фоне сиреневые акценты Material You.
 * Собственных цветов у Material здесь не остаётся ни одного.
 */
@Composable
fun KcalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkKcalColors else LightKcalColors
    val type = KcalTypography()

    CompositionLocalProvider(
        LocalKcalColors provides colors,
        LocalKcalTypography provides type,
    ) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(darkTheme),
            typography = type.toMaterialTypography(),
            content = content,
        )
    }
}

/**
 * Токены в роли Material.
 *
 * Отображение прямое, кроме одного места: `primary` — цвет пузырька, то есть
 * в тёмной теме он светлый. Material от этого не страдает, потому что `onPrimary`
 * отображён ему в пару.
 */
private fun KcalColors.toColorScheme(darkTheme: Boolean) =
    (if (darkTheme) darkColorScheme() else lightColorScheme()).copy(
        primary = bubble,
        onPrimary = onBubble,
        primaryContainer = chip,
        onPrimaryContainer = text,
        secondary = text2,
        onSecondary = bg,
        secondaryContainer = chip,
        onSecondaryContainer = text,
        tertiary = carb.fill,
        onTertiary = bg,
        tertiaryContainer = carb.tile,
        onTertiaryContainer = carb.text,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = chip,
        onSurfaceVariant = text2,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = chip,
        surfaceTint = bubble,
        inverseSurface = bubble,
        inverseOnSurface = onBubble,
        outline = text3,
        outlineVariant = line,
        error = error,
        onError = bg,
        errorContainer = error.copy(alpha = 0.12f),
        onErrorContainer = error,
        // Затемнение под шитом — единственный цвет, который темой не переворачивается:
        // в тёмной теме `bubble` светлый, и подложка вышла бы белой вспышкой.
        scrim = Color(0xFF1C1914).copy(alpha = 0.4f),
    )

/**
 * Наши кегли в шкалу Material.
 *
 * Заполнены все роли, даже те, которыми мы не пользуемся: забытый где-то
 * `MaterialTheme.typography` должен принести наш шрифт, а не системный.
 */
private fun KcalTypography.toMaterialTypography() = Typography(
    displayLarge = hero,
    displayMedium = hero,
    displaySmall = hero,
    headlineLarge = title,
    headlineMedium = title,
    headlineSmall = title,
    titleLarge = title,
    titleMedium = bubbleTitle,
    titleSmall = bubbleTitle,
    bodyLarge = input,
    bodyMedium = body,
    bodySmall = body,
    labelLarge = input,
    labelMedium = label,
    labelSmall = label,
)
