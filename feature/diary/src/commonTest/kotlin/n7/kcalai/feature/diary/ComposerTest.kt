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

    /** Похожий из справочника по названию с пачки — не найденный по коду и не сохранённый. */
    @Test
    fun `похожий по названию с пачки так и подписан`() {
        assertEquals(
            "похоже по названию с пачки · допишите вес",
            scannedCaption(scanned(EntrySource.VISION, guessed = true)),
        )
    }

    /**
     * Вес и отсканированный товар — ровно те случаи, где ✓ записывает и без нейросети
     * (см. [DiaryViewModel.onSubmit]): чипс один, выбирать не из чего. Тянуться к нему
     * мимо клавиатуры незачем — кнопка под пальцем становится ✓.
     */
    @Test
    fun `кнопка записывает вес и отсканированный товар`() {
        assertEquals(ComposerAction.RECORD, composerAction(ComposerRow.Weight(grams = 72_400), "72 кг", Assist.NONE))
        assertEquals(
            ComposerAction.RECORD,
            composerAction(ComposerRow.Scanned(scanned(EntrySource.BARCODE, guessed = false)), "Сыр 30", Assist.NONE),
        )
    }

    /** Без нейросети выбор в выдаче поиска за человека не делается: кнопка — сканер. */
    @Test
    fun `без нейросети фраза оставляет кнопку сканером`() {
        assertEquals(ComposerAction.SCAN, composerAction(ComposerRow.None, "", Assist.NONE))
        assertEquals(ComposerAction.SCAN, composerAction(ComposerRow.Predictions(emptyList()), "", Assist.NONE))
        assertEquals(
            ComposerAction.SCAN,
            composerAction(ComposerRow.Results(listOf(scanned(EntrySource.MANUAL, guessed = true))), "гречка 200", Assist.NONE),
        )
    }

    /** С нейросетью — как в мессенджере: набрал фразу, кнопка «отправить» отдаёт её на разбор. */
    @Test
    fun `с нейросетью любая фраза отправляется на разбор`() {
        assertEquals(
            ComposerAction.RECORD,
            composerAction(ComposerRow.Results(listOf(scanned(EntrySource.MANUAL, guessed = true))), "гречка 200", Assist.OFFER),
        )
        assertEquals(ComposerAction.RECORD, composerAction(ComposerRow.None, "доел за ребёнком макароны", Assist.OFFER))
    }

    /**
     * Отправлять нечего: строка без букв — начало «200 г гречки»; фразу нейросеть уже
     * не поняла; её ответ уже стоит чипсами — дальше тап по чипсу, а не второй разбор.
     */
    @Test
    fun `с нейросетью кнопка остаётся сканером, когда отправлять нечего`() {
        assertEquals(ComposerAction.SCAN, composerAction(ComposerRow.None, "", Assist.OFFER))
        assertEquals(ComposerAction.SCAN, composerAction(ComposerRow.None, "200", Assist.OFFER))
        assertEquals(ComposerAction.SCAN, composerAction(ComposerRow.None, "хрючево", Assist.FAILED))
        assertEquals(
            ComposerAction.SCAN,
            composerAction(ComposerRow.Results(listOf(scanned(EntrySource.LLM, guessed = true))), "тарелка борща", Assist.OFFER),
        )
    }
}
