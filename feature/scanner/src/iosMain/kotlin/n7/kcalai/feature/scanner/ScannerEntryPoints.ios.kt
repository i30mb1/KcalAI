package n7.kcalai.feature.scanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import n7.kcalai.model.Nutriments
import n7.kcalai.repositories.LabelReading

// Камеры на iOS пока нет: сканер сразу закрывается, и человек остаётся в ручном вводе.

@Composable
actual fun ScannerDialog(
    step: ScanStep,
    onScanned: (String) -> Unit,
    onSave: (gtin: String?, name: String, nutriments: Nutriments) -> Unit,
    onDismiss: () -> Unit,
    frameStats: Boolean,
) {
    LaunchedEffect(Unit) { onDismiss() }
}

@Composable
actual fun LabelDebugDialog(onRead: (LabelReading) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onDismiss() }
}
