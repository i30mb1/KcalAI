package n7.kcalai.feature.diary

import n7.kcalai.model.EntrySource
import n7.kcalai.resolver.ResolvedItem
import kotlin.test.assertEquals
import kotlin.test.Test

/**
 * Подпись над рядом подсказок называет то, что произошло, а не что-то похожее.
 * Кнопка у строки ввода делает то же, что ✓ на клавиатуре, когда записать есть что.
 */
class ComposerTest {

    private fun scanned(source: EntrySource, guessed: Boolean) = ResolvedItem(
        sourceText = "Сыр",
        candidate = null,
        grams = 100,
        confidence = 1f,
        source = source,
        gramsGuessed = guessed,
    )

    @Test
    fun `найденное по коду так и подписано`() {
        assertEquals("найдено по коду · допишите вес", scannedCaption(scanned(EntrySource.BARCODE, guessed = true)))
        assertEquals("найдено по коду", scannedCaption(scanned(EntrySource.BARCODE, guessed = false)))
    }

    /**
     * Продукт, только что заведённый с этикетки, по коду не находили — его
     * сохранили. «Найдено по коду» над ним путало: кода у развесного нет вовсе.
     */
    @Test
    fun `заведённый с этикетки продукт найденным по коду не называется`() {
        assertEquals("продукт сохранён · допишите вес", scannedCaption(scanned(EntrySource.MANUAL, guessed = true)))
        assertEquals("продукт сохранён", scannedCaption(scanned(EntrySource.MANUAL, guessed = false)))
    }

    /**
     * Вес и отсканированный товар — ровно те случаи, где ✓ на клавиатуре записывает
     * (см. [DiaryViewModel.onSubmit]): чипс один, выбирать не из чего. Тянуться к нему
     * мимо клавиатуры незачем — кнопка под пальцем становится «записать».
     */
    @Test
    fun `кнопка записывает вес и отсканированный товар`() {
        assertEquals(ComposerAction.RECORD, composerAction(ComposerRow.Weight(grams = 72_400)))
        assertEquals(ComposerAction.RECORD, composerAction(ComposerRow.Scanned(scanned(EntrySource.BARCODE, guessed = false))))
    }

    /**
     * В выдаче поиска выбор между вариантами, и делать его за человека кнопка не будет:
     * там, как и в пустой строке, она по-прежнему открывает сканер.
     */
    @Test
    fun `в остальных случаях кнопка открывает сканер`() {
        assertEquals(ComposerAction.SCAN, composerAction(ComposerRow.None))
        assertEquals(ComposerAction.SCAN, composerAction(ComposerRow.Predictions(emptyList())))
        assertEquals(ComposerAction.SCAN, composerAction(ComposerRow.Results(listOf(scanned(EntrySource.MANUAL, guessed = true)))))
    }
}
