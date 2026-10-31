package n7.kcalai.feature.scanner

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Карточка нового продукта: что считается заполненным.
 *
 * Подпись под названием говорит «N из 5 полей» — название и четыре числа
 * с этикетки. Штрихкод необязателен и стоит отдельной строкой: засчитай его,
 * и после промаха скана карточка открывается с «1 из 5», а заполненная
 * целиком показывает «6 из 5».
 */
class ScanStateTest {

    private fun filled(gtin: String? = GTIN) = ScanState(gtin).apply {
        name.type("Творог 5%")
        kcal.type("121")
        prot.type("16")
        fat.type("5")
        carb.type("3")
    }

    @Test
    fun `заполненная карточка — пять из пяти, сколько бы ни было в коде`() {
        assertEquals(5, filled(gtin = GTIN).filledFields)
        assertEquals(5, filled(gtin = null).filledFields)
    }

    @Test
    fun `код с промаха скана полем карточки не считается`() {
        assertEquals(0, ScanState(GTIN).filledFields)
    }

    /**
     * Закрыть карточку — одно касание «назад», а заполнить её — минута с пачкой
     * в руке. Пока терять нечего, карточка закрывается сразу; есть что терять —
     * сначала спрашивает.
     */
    @Test
    fun `терять нечего, пока в карточке только код с промаха скана`() {
        assertFalse(ScanState(GTIN).hasDraft)
        assertFalse(ScanState(null).hasDraft)
    }

    @Test
    fun `есть что терять, как только поле заполнила камера или человек`() {
        assertTrue(ScanState(GTIN).apply { name.type("Творог") }.hasDraft)
        assertTrue(ScanState(null).apply { kcal.offer("121") }.hasDraft)
        // Код, набранный руками на карточке без промаха скана, — тоже работа.
        assertTrue(ScanState(null).apply { barcode.type(GTIN) }.hasDraft)
    }

    // --- Недостающие числа вписывает человек ------------------------------------------

    /**
     * Пустое поле — не ноль. Раньше продукт сохранялся с одними калориями,
     * а недочитанные белки, жиры и углеводы молча становились нулями — и потом
     * годами врали в дневнике. Чего камера не прочла, человек вписывает сам.
     */
    @Test
    fun `без белков, жиров и углеводов продукт не сохраняется`() {
        val state = ScanState(null).apply {
            name.type("Творог 5%")
            kcal.type("121")
        }

        assertFalse(state.canSave)
        assertEquals(listOf("белки", "жиры", "углеводы"), state.missing)
    }

    /** Ноль, вписанный руками, — это ответ: у масла белков действительно нет. */
    @Test
    fun `вписанный руками ноль — заполненное поле`() {
        val state = ScanState(null).apply {
            name.type("Масло подсолнечное")
            kcal.type("899")
            prot.type("0")
            fat.type("99,9")
            carb.type("0")
        }

        assertTrue(state.missing.isEmpty())
        assertTrue(state.canSave)
    }

    @Test
    fun `подсказка называет именно то, чего не хватает`() {
        val state = ScanState(null).apply {
            name.type("Творог 5%")
            kcal.type("121")
            fat.type("5")
        }

        assertEquals(
            "Не хватает белков и углеводов. Наведите камеру на эти строки или впишите с пачки — " +
                "если строки на пачке нет, впишите 0.",
            state.saveHint,
        )
    }

    @Test
    fun `без названия подсказка просит сначала назвать продукт`() {
        assertEquals("Назовите продукт — без названия его потом не найти", filled().apply { name.type("") }.saveHint)
    }

    /**
     * Сверка по Этуотеру — когда есть что сверять. Пока белки, жиры и углеводы
     * не вписаны, «выходит 0 ккал, а указано 121» — не замечание, а упрёк
     * человеку, который ещё не дописал.
     */
    @Test
    fun `сверка калорий с макросами молчит, пока числа не вписаны все`() {
        val partial = ScanState(null).apply {
            name.type("Творог 5%")
            kcal.type("121")
        }
        assertEquals(null, partial.warning)

        val mismatched = filled().apply { kcal.type("400") }
        assertTrue(mismatched.warning?.contains("Проверьте цифры") == true)
    }

    // --- Подсказка над камерой ------------------------------------------------------------

    /**
     * «Кадр 48 · 542 мс» над камерой — телеметрия для разработчика: человеку она
     * ничего не говорит. Что съёмка идёт, ему говорит подсказка — она стоит, пока
     * камера не прочла первое поле, а не гаснет после первого же кадра.
     */
    @Test
    fun `подсказка держится, пока камера ничего не прочла`() {
        assertEquals(TABLE_HINT, scanHint(camera = true, answered = true, ocr = true, seen = 40, cameraFilled = 0, stats = false))
        assertNull(scanHint(camera = true, answered = true, ocr = true, seen = 40, cameraFilled = 1, stats = false))
    }

    /** У разработчика на этом месте счётчик кадров — подсказка ему уже ни к чему. */
    @Test
    fun `со счётчиком кадров подсказка гаснет после первого кадра`() {
        assertEquals(TABLE_HINT, scanHint(camera = true, answered = true, ocr = true, seen = 0, cameraFilled = 0, stats = true))
        assertNull(scanHint(camera = true, answered = true, ocr = true, seen = 1, cameraFilled = 0, stats = true))
    }

    @Test
    fun `без камеры и без распознавания подсказка говорит, что заполнять самим`() {
        assertEquals("Камеры нет — заполните поля сами", scanHint(camera = false, answered = true, ocr = true, seen = 0, cameraFilled = 0, stats = false))
        assertEquals("Запрашиваем доступ к камере", scanHint(camera = false, answered = false, ocr = true, seen = 0, cameraFilled = 0, stats = false))
        assertEquals("Распознавание недоступно — заполните сами", scanHint(camera = true, answered = true, ocr = false, seen = 0, cameraFilled = 0, stats = false))
    }

    private companion object {
        const val GTIN = "4607001771234"
        const val TABLE_HINT = "Держите таблицу пищевой ценности в рамке"
    }
}
