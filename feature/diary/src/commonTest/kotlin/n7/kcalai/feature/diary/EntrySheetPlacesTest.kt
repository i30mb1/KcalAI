package n7.kcalai.feature.diary

import n7.kcalai.model.Mascot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Шит записи: порция и продукт — две разные правки.
 *
 * Порция (вес, приём пищи) правится молча. Правку самого продукта ведёт Яйцо —
 * оно отвечает за общую базу и говорит, куда уйдут цифры. Персонаж на экране один:
 * пока Яйцо не говорит, все стоят за сценой.
 */
class EntrySheetPlacesTest {

    @Test
    fun `в правке порции никто не стоит на сцене`() {
        val places = entrySheetPlaces(productMode = false)

        assertEquals(Mascot.entries.toSet(), places.keys)
        assertEquals(setOf(MascotPlace.Offstage), places.values.toSet())
    }

    @Test
    fun `в правке продукта Яйцо говорит у реплики и остальные за сценой`() {
        val places = entrySheetPlaces(productMode = true)

        assertEquals(MascotPlace.Reply(SHEET_SPEECH_ANCHOR), places.getValue(Mascot.EGG))
        assertEquals(
            setOf(MascotPlace.Offstage),
            Mascot.entries.filter { it != Mascot.EGG }.map { places.getValue(it) }.toSet(),
        )
    }

    @Test
    fun `продукт с кодом Яйцо обещает отправить в общую базу`() {
        val line = productSpeech(hasBarcode = true)

        assertEquals(Mascot.EGG, line.mascot)
        assertTrue("общую базу" in line.text)
    }

    @Test
    fun `продукт без кода остаётся в дневнике и об общей базе речи нет`() {
        val line = productSpeech(hasBarcode = false)

        assertEquals(Mascot.EGG, line.mascot)
        assertFalse("общую базу" in line.text)
    }
}
