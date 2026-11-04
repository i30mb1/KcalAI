package n7.kcalai.feature.diary

import n7.kcalai.model.EntrySource
import n7.kcalai.resolver.ResolvedItem
import kotlin.test.assertEquals
import kotlin.test.Test

/**
 * Подпись над рядом подсказок называет то, что произошло, а не что-то похожее.
 * Кнопка у строки ввода: набрано что-то — «отправить», пусто — сканер.
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

    /** Похожий из справочника по названию с пачки — не найденный по коду и не сохранённый. */
    @Test
    fun `похожий по названию с пачки так и подписан`() {
        assertEquals(
            "похоже по названию с пачки · допишите вес",
            scannedCaption(scanned(EntrySource.VISION, guessed = true)),
        )
    }

    /** Кнопка зависит только от строки: человек не должен гадать, почему она сейчас такая. */
    @Test
    fun `что-то набрано — кнопка отправляет`() {
        assertEquals(ComposerAction.RECORD, composerAction("г"))
        assertEquals(ComposerAction.RECORD, composerAction("гречка 200"))
        assertEquals(ComposerAction.RECORD, composerAction("72 кг"))
        assertEquals(ComposerAction.RECORD, composerAction("200"))
    }

    @Test
    fun `пустая строка — кнопка сканирует`() {
        assertEquals(ComposerAction.SCAN, composerAction(""))
        assertEquals(ComposerAction.SCAN, composerAction("   "))
    }
}
