package n7.kcalai.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

actual val frostSupported: Boolean = false

actual fun Modifier.frost(blur: Dp, saturation: Float): Modifier = this
