package n7.kcalai.feature.scanner

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test
import n7.kcalai.model.Mascot
import n7.kcalai.repositories.LabelReading

/**
 * Карточка нового продукта: что считается заполненным, когда персонажи просят
 * вписать числа руками, куда встают цифры с панели и кто из троих что говорит.
 */
class ScanStateTest {

    private fun filled(gtin: String? = GTIN) = ScanState(gtin).apply {
        name.type("Творог 5%")
        kcal.type("121")
        prot.type("16")
        fat.type("5")
        carb.type("3")
    }

    /** Кадр, по которому камера дочитала числа. Макросы — в сотых грамма, как у разбора. */
    private fun read(
        kcal: Int? = null,
        prot: Int? = null,
        fat: Int? = null,
        carb: Int? = null,
        sharp: Boolean = true,
    ) = LabelFrame(
        reading = LabelReading.EMPTY,
        elapsedMs = 0,
        fields = listOf("ккал" to settled(kcal), "Б" to settled(prot), "Ж" to settled(fat), "У" to settled(carb)),
        sharp = sharp,
    )

    private fun settled(value: Int?) =
        LabelConsensus.Field(value, votes = if (value != null) 3 else 0, needed = 3)

    private fun ScanState.pressAll(keys: String) = keys.forEach { key ->
        press(if (key == ',') PadKey.Comma else PadKey.Digit(key.digitToInt()))
    }

    private fun ScanState.line(mascot: Mascot): MascotLine = mascotLines().single { it.mascot == mascot }

    /**
     * Штрихкод необязателен и полем карточки не считается: засчитай его — и после
     * промаха скана в карточке «что-то уже есть» ещё до того, как камера прочла
     * хоть строку, а закрыть пустую карточку без вопроса было бы нельзя.
     */
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

    // --- Камера не справилась — просят вписать руками ------------------------------------

    @Test
    fun `камера ничего не прочла за 20 секунд — просят вписать, цифры встают на белки`() {
        var now = 0L
        val state = ScanState(GTIN) { now }

        now = 19_999
        state.tick()
        assertFalse(state.manual)

        now = 20_000
        state.tick()
        assertTrue(state.manual)
        assertSame(state.prot, state.typing)
    }

    /** Двадцать секунд — без нового числа, а не с открытия: медленную камеру не перебивают. */
    @Test
    fun `прочитанное число откладывает просьбу`() {
        var now = 0L
        val state = ScanState(GTIN) { now }

        now = 15_000
        state.onFrame(read(prot = 1240))
        state.tick()

        now = 34_999
        state.tick()
        assertFalse(state.manual)

        now = 35_000
        state.tick()
        assertTrue(state.manual)
        // Белки уже прочитаны — цифры встают на следующую строку этикетки.
        assertSame(state.fat, state.typing)
    }

    /** Мыльный кадр распознавание пропускает; шесть секунд одного мыла — ждать нечего. */
    @Test
    fun `камера мылит шесть секунд подряд — просят вписать раньше`() {
        var now = 0L
        val state = ScanState(GTIN) { now }

        now = 1_000
        state.onFrame(read(sharp = false))

        now = 6_999
        state.tick()
        assertFalse(state.manual)

        now = 7_000
        state.tick()
        assertTrue(state.manual)
    }

    @Test
    fun `резкий кадр начинает отсчёт мыла заново`() {
        var now = 0L
        val state = ScanState(GTIN) { now }

        now = 1_000
        state.onFrame(read(sharp = false))
        now = 5_000
        state.onFrame(read(sharp = true))
        now = 6_000
        state.onFrame(read(sharp = false))

        now = 11_999
        state.tick()
        assertFalse(state.manual)

        now = 12_000
        state.tick()
        assertTrue(state.manual)
    }

    /** Числа прочитаны все — минута над названием не повод просить вписать то, что уже есть. */
    @Test
    fun `просят вписать только недочитанное число`() {
        var now = 0L
        val withoutCarbs = ScanState(GTIN) { now }.apply { onFrame(read(kcal = 121, prot = 1600, fat = 500)) }
        val allRead = ScanState(GTIN) { now }.apply { onFrame(read(kcal = 121, prot = 1600, fat = 500, carb = 300)) }

        now = 60_000
        withoutCarbs.tick()
        allRead.tick()

        assertSame(withoutCarbs.carb, withoutCarbs.typing)
        assertFalse(allRead.manual)
    }

    /** Спрятал цифры — значит, хочет ещё раз камерой. Она получает свои двадцать секунд. */
    @Test
    fun `спрятанные цифры возвращают камере двадцать секунд`() {
        var now = 0L
        val state = ScanState(GTIN) { now }
        now = 20_000
        state.tick()

        state.stopTyping()
        assertNull(state.typing)
        assertFalse(state.manual)

        now = 39_999
        state.tick()
        assertFalse(state.manual)

        now = 40_000
        state.tick()
        assertTrue(state.manual)
    }

    /** Человек набирает название — цифры не выскакивают посреди слова и не прячут клавиатуру. */
    @Test
    fun `пока набирают название, просьба вписать числа ждёт`() {
        var now = 0L
        val state = ScanState(GTIN) { now }

        state.startTyping(state.name)
        now = 25_000
        state.tick()

        assertSame(state.name, state.typing)
        assertFalse(state.manual)
    }

    @Test
    fun `без камеры цифры открываются сразу`() {
        val state = ScanState(GTIN)

        state.withoutCamera()

        assertTrue(state.manual)
        assertSame(state.prot, state.typing)
    }

    /** Нативной библиотеки распознавания под этот процессор может не быть — ждать нечего. */
    @Test
    fun `распознавание недоступно — цифры открываются с первым же кадром`() {
        val state = ScanState(GTIN)

        state.onFrame(LabelFrame(LabelReading.EMPTY, elapsedMs = 0, available = false))

        assertTrue(state.manual)
        assertSame(state.prot, state.typing)
    }

    // --- Цифры с панели -----------------------------------------------------------------

    @Test
    fun `цифры встают в выбранное поле, и камера их больше не трогает`() {
        val state = ScanState(GTIN)

        state.startTyping(state.carb)
        state.pressAll("3,3")
        state.onFrame(read(carb = 550))

        assertEquals("3,3", state.carb.text)
    }

    /**
     * Камера прочла «5,5», человек стёр — и тут же следующий кадр вписывал «5,5»
     * обратно. Поле, которое сейчас набирают, камера не трогает вовсе.
     */
    @Test
    fun `в поле, которое сейчас набирают, камера не пишет — даже в стёртое`() {
        val state = ScanState(GTIN)
        state.onFrame(read(fat = 550))

        state.startTyping(state.fat)
        repeat(3) { state.press(PadKey.Erase) }
        state.onFrame(read(fat = 550))

        assertEquals("", state.fat.text)
    }

    /** Число распознано, но разбор не понял, чьё оно: тап по нему — в поле, которое набирают. */
    @Test
    fun `распознанное число тапом встаёт в набираемое поле`() {
        val state = ScanState(GTIN)

        state.startTyping(state.fat)
        state.put("2,5")

        assertEquals("2,5", state.fat.text)
    }

    @Test
    fun `калории набираются без запятой, а код — с ведущим нулём`() {
        val state = ScanState(null)

        state.startTyping(state.kcal)
        state.pressAll("1,2")
        state.startTyping(state.barcode)
        state.pressAll("04")

        assertEquals("12", state.kcal.text)
        assertEquals("04", state.barcode.text)
    }

    // --- Кто что говорит -----------------------------------------------------------------

    /** Белки — Яйцо, жиры — Масло, углеводы — Зёрнышко: тот же, кто сидит в плитке дневника. */
    @Test
    fun `каждый ждёт своё, а получив, называет число и ждёт остальных`() {
        val state = ScanState(GTIN)
        assertTrue(state.mascotLines().all { it.mood == MascotMood.ASKING })

        state.onFrame(read(prot = 1240))
        assertEquals(MascotMood.WAITING, state.line(Mascot.EGG).mood)
        assertTrue("12,4" in state.line(Mascot.EGG).text)
        assertEquals(MascotMood.ASKING, state.line(Mascot.BUTTER).mood)
        assertEquals(MascotMood.ASKING, state.line(Mascot.GRAIN).mood)

        state.onFrame(read(prot = 1240, fat = 500, carb = 330))
        assertTrue("5" in state.line(Mascot.BUTTER).text)
        assertTrue("3,3" in state.line(Mascot.GRAIN).text)
    }

    @Test
    fun `камера не справилась — у кого пусто, просят вписать, у кого есть — ждут`() {
        var now = 0L
        val state = ScanState(GTIN) { now }
        state.onFrame(read(prot = 1240))
        now = 30_000
        state.tick()

        assertEquals(MascotMood.WAITING, state.line(Mascot.EGG).mood)
        assertEquals(MascotMood.TYPING, state.line(Mascot.BUTTER).mood)
        assertEquals(MascotMood.TYPING, state.line(Mascot.GRAIN).mood)
    }

    @Test
    fun `всё заполнено и сходится — трое просят проверить и сохранить`() {
        assertTrue(filled().mascotLines().all { it.mood == MascotMood.DONE })
    }

    /** 950 ккал не бывает — сохранять такое нельзя, и звать к этому персонажи не должны. */
    @Test
    fun `цифры не сходятся — Яйцо просит проверить, сохранять никто не зовёт`() {
        val state = filled().apply { kcal.type("950") }

        assertEquals(MascotMood.ASKING, state.line(Mascot.EGG).mood)
        assertTrue(state.mascotLines().none { it.mood == MascotMood.DONE })
    }

    /** Калории — не макрос, своего персонажа у них нет; о них говорит Яйцо, староста. */
    @Test
    fun `остались одни калории — о них говорит Яйцо`() {
        val state = ScanState(GTIN).apply { name.type("Творог 5%") }
        state.onFrame(read(prot = 1600, fat = 500, carb = 300))

        assertTrue("калори" in state.line(Mascot.EGG).text)
    }

    @Test
    fun `осталось одно название — Яйцо спрашивает, как называется продукт`() {
        val state = ScanState(GTIN)
        state.onFrame(read(kcal = 121, prot = 1600, fat = 500, carb = 300))

        assertEquals(MascotMood.ASKING, state.line(Mascot.EGG).mood)
        assertTrue("называ" in state.line(Mascot.EGG).text)
    }

    // --- Подсказка в панели ---------------------------------------------------------------

    @Test
    fun `подсказка учит наводить камеру, пока камера читает, и молчит, когда всё готово`() {
        assertEquals(
            TABLE_HINT,
            panelHint(camera = true, answered = true, ocr = true, manual = false, numbersMissing = true, nameMissing = true),
        )
        assertNull(panelHint(camera = true, answered = true, ocr = true, manual = false, numbersMissing = false, nameMissing = false))
    }

    /** У сахара строк «Белки» и «Жиры» нет вовсе — и человек ищет, что вписать в поле, которого на пачке нет. */
    @Test
    fun `когда вписывают руками, подсказка говорит про строку, которой нет на пачке`() {
        assertEquals(
            "Если строки на пачке нет — впишите 0",
            panelHint(camera = true, answered = true, ocr = true, manual = true, numbersMissing = true, nameMissing = true),
        )
    }

    /** Числа вписаны — «впишите 0» больше не к месту: осталось назвать продукт. */
    @Test
    fun `числа есть, названия нет — подсказка просит назвать продукт`() {
        assertEquals(
            "Назовите продукт — без названия его потом не найти",
            panelHint(camera = true, answered = true, ocr = true, manual = true, numbersMissing = false, nameMissing = true),
        )
        // Камеры нет, но числа уже вписаны — говорить про камеру поздно.
        assertEquals(
            "Назовите продукт — без названия его потом не найти",
            panelHint(camera = false, answered = true, ocr = true, manual = true, numbersMissing = false, nameMissing = true),
        )
    }

    @Test
    fun `без камеры и без распознавания подсказка говорит, что вписывать самим`() {
        assertEquals(
            "Камеры нет — впишите числа с пачки",
            panelHint(camera = false, answered = true, ocr = true, manual = true, numbersMissing = true, nameMissing = true),
        )
        assertEquals(
            "Запрашиваем доступ к камере",
            panelHint(camera = false, answered = false, ocr = true, manual = false, numbersMissing = true, nameMissing = true),
        )
        assertEquals(
            "Распознавание недоступно — впишите числа с пачки",
            panelHint(camera = true, answered = true, ocr = false, manual = true, numbersMissing = true, nameMissing = true),
        )
    }

    private companion object {
        const val GTIN = "4607001771234"
        const val TABLE_HINT = "Держите таблицу КБЖУ в рамке — или нажмите на поле и впишите сами"
    }
}
