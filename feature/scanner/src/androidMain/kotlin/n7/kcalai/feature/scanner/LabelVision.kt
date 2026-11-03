package n7.kcalai.feature.scanner

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf
import n7.kcalai.model.ProductDraft

/**
 * Второе мнение по этикетке — нейросеть, которая смотрит на тот же кадр.
 *
 * Интерфейс, а не зависимость от ML Kit: сканер не знает, кто читает, и без
 * читателя работает как прежде. Ответ проверяет карточка, см. [ScanState.onVision].
 */
fun interface LabelVision {
    /** Кадр этикетки -> что на ней напечатано. `null` — не прочла. */
    suspend fun read(label: Bitmap): ProductDraft?
}

/** Читатель этикеток, если он есть на этом телефоне. Задаёт приложение. */
val LocalLabelVision = staticCompositionLocalOf<LabelVision?> { null }
