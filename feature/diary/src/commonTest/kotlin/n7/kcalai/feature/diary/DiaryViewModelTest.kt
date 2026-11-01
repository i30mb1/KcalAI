package n7.kcalai.feature.diary

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import n7.kcalai.feature.scanner.ScanStep
import n7.kcalai.model.MealType
import n7.kcalai.model.Mascot
import n7.kcalai.database.WriteConsent
import n7.kcalai.database.MascotProfileEntity
import n7.kcalai.mascot.MascotEvent
import n7.kcalai.mascot.CueKind
import n7.kcalai.database.DailyGoalEntity
import kotlinx.coroutines.test.runCurrent

/**
 * Экранная модель дневника глазами человека: что он набрал, что нажал
 * и что после этого стоит в ленте и в дневнике.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = DiaryHarness()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Модель с подписчиком на состояние: без него `stateIn` не считает ленту. */
    private fun TestScope.screen(): DiaryViewModel =
        harness.viewModel(dispatcher).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

    private fun DiaryViewModel.type(text: String) = onInputChange(text)

    @Test
    fun `вес из строки ввода записывается тапом по чипсу`() = runTest(dispatcher) {
        val vm = screen()

        vm.type("вес 82,4")
        advanceUntilIdle()
        vm.onLogWeight()
        advanceUntilIdle()

        assertEquals(82_400, harness.bodyMetricDao.weights[harness.today])
        assertEquals("", vm.state.value.input)
        assertTrue(vm.state.value.feed.any { it is FeedItem.WeightLogged })
    }

    /** Помощь с целью начинается с веса, который человек уже писал: «вес 82,4». */
    @Test
    fun `шит цели знает последний записанный вес`() = runTest(dispatcher) {
        harness.bodyMetricDao.weights[harness.today - 3] = 82_400
        harness.bodyMetricDao.weights[harness.today - 9] = 83_100
        val vm = screen()

        assertEquals(82_400, vm.state.value.lastWeightGrams)
    }

    // --- Очередь отправки --------------------------------------------------------------

    /**
     * «Не отправлено на сервер… Отправить» — предложение, которое обязано
     * сработать. Пока сервер не отвечает, кнопка кончилась бы «сервер не ответил»,
     * и пузырёк был бы только шумом в ленте.
     */
    @Test
    fun `пузырёк про отправку молчит, пока сервер не отвечает`() = runTest(dispatcher) {
        val outbox = FakeOutbox(OutboxCount(scans = 2, products = 1), serverUp = false)
        val vm = harness.viewModel(dispatcher, outbox).also { backgroundScope.launch { it.state.collect {} } }
        vm.onResume()
        advanceUntilIdle()

        assertTrue(vm.state.value.feed.none { it is FeedItem.Outbox })
    }

    @Test
    fun `пузырёк про отправку появляется, когда сервер отвечает`() = runTest(dispatcher) {
        val outbox = FakeOutbox(OutboxCount(scans = 2, products = 1), serverUp = false)
        val vm = harness.viewModel(dispatcher, outbox).also { backgroundScope.launch { it.state.collect {} } }
        advanceUntilIdle()

        // Сервер поднялся, пока экран был в фоне: возвращение на экран это замечает.
        outbox.serverUp = true
        vm.onResume()
        advanceUntilIdle()

        assertTrue(vm.state.value.feed.any { it is FeedItem.Outbox })
    }

    // --- Холодный старт ------------------------------------------------------------------

    /**
     * Пока база не ответила, дня ещё нет — и рисовать вместо него пустой день
     * нельзя. Раньше на каждом холодном старте полсекунды висело «0 съедено»
     * и «Цель на сегодня не задана», а потом настоящие записи выпрыгивали
     * с анимацией, будто их только что добавили.
     */
    @Test
    fun `пока день не прочитан из базы, лента не выдумывает пустой день`() = runTest(dispatcher) {
        harness.addEntry("Гречка отварная", grams = 200, meal = n7.kcalai.model.MealType.LUNCH)
        harness.diaryDao.holdDay()
        val vm = screen()

        assertFalse(vm.state.value.loaded)
        assertTrue(vm.state.value.feed.isEmpty())

        harness.diaryDao.releaseDay()
        advanceUntilIdle()

        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.feed.any { it is FeedItem.Meal })
        assertTrue(vm.state.value.feed.none { it is FeedItem.Summary })
    }

    // --- Отмена удаления ------------------------------------------------------------------

    /**
     * Удаление идёт без вопроса «вы уверены?», и это правильно — но только пока
     * ошибку можно отменить. Без отмены случайный тап стирает запись насовсем.
     */
    @Test
    fun `удалённую запись возвращает «Вернуть» — та же запись, на том же месте`() = runTest(dispatcher) {
        val entry = harness.addEntry("Гречка отварная", grams = 200, meal = n7.kcalai.model.MealType.LUNCH)
        val vm = screen()

        vm.onDeleteEntry(entry)
        advanceUntilIdle()

        assertTrue(harness.diaryDao.day(harness.today).isEmpty())
        val deleted = vm.state.value.feed.filterIsInstance<FeedItem.Deleted>().single()
        assertEquals(entry, deleted.entry)

        vm.onUndoDelete(deleted)
        advanceUntilIdle()

        assertEquals(listOf(entry), harness.diaryDao.day(harness.today))
        assertTrue(vm.state.value.feed.none { it is FeedItem.Deleted })
    }

    /**
     * Реплика «Удалил…» уходит из ленты после отмены. Номер следующей реплики
     * не вправе совпасть с номером уже стоящей: `LazyColumn` на повторном
     * ключе падает.
     */
    @Test
    fun `ключи реплик не повторяются после отмены удаления`() = runTest(dispatcher) {
        val entry = harness.addEntry("Яйцо", grams = 120, meal = n7.kcalai.model.MealType.BREAKFAST)
        val vm = screen()

        vm.onDeleteEntry(entry)
        advanceUntilIdle()
        vm.type("вес 82,4")
        vm.onLogWeight()
        advanceUntilIdle()
        vm.onUndoDelete(vm.state.value.feed.filterIsInstance<FeedItem.Deleted>().single())
        advanceUntilIdle()
        vm.type("вес 82,2")
        vm.onLogWeight()
        advanceUntilIdle()

        val keys = vm.state.value.feed.map { it.key }
        assertEquals(keys.distinct(), keys)
    }

    // --- ✓ на клавиатуре --------------------------------------------------------------------

    @Test
    fun `✓ на клавиатуре записывает вес, как тап по чипсу`() = runTest(dispatcher) {
        val vm = screen()

        vm.type("вес 82,4")
        advanceUntilIdle()
        vm.onSubmit()
        advanceUntilIdle()

        assertEquals(82_400, harness.bodyMetricDao.weights[harness.today])
        assertEquals("", vm.state.value.input)
    }

    /** После скана человек дописывает вес и жмёт ✓ — это и есть «добавить». */
    @Test
    fun `✓ на клавиатуре добавляет отсканированный товар с набранным весом`() = runTest(dispatcher) {
        harness.userFoodDao.add(barcode = GTIN, name = "Сыр", kcal100 = 350)
        val vm = screen()

        vm.onScanned(GTIN)
        advanceUntilIdle()
        vm.type(vm.state.value.input + "50")
        advanceUntilIdle()
        vm.onSubmit()
        advanceUntilIdle()

        val added = harness.diaryDao.day(harness.today).single()
        assertEquals("Сыр", added.displayName)
        assertEquals(50, added.grams)
        assertEquals("", vm.state.value.input)
    }

    /** Выдача поиска — это выбор, и делать его за человека ✓ не вправе. */
    @Test
    fun `✓ на клавиатуре не выбирает из выдачи поиска за человека`() = runTest(dispatcher) {
        val vm = screen()

        vm.type("гречка 200")
        advanceUntilIdle()
        vm.onSubmit()
        advanceUntilIdle()

        assertTrue(harness.diaryDao.day(harness.today).isEmpty())
        assertEquals("гречка 200", vm.state.value.input)
    }

    // --- «Ничего не нашлось» ----------------------------------------------------------------

    /**
     * «200» — это начало «200 г гречки», а не блюдо, и случайная точка — тоже
     * не блюдо. Отвечать на них «ничего не нашлось» значит перебивать человека
     * на полуслове.
     */
    @Test
    fun `строка без единой буквы — ещё не блюдо, и «не нашлось» не появляется`() = runTest(dispatcher) {
        val vm = screen()

        listOf("200", ".", "2,5").forEach { text ->
            vm.type(text)
            advanceUntilIdle()
            assertTrue(vm.state.value.feed.none { it is FeedItem.NotFound }, "на «$text»")
        }

        vm.type("кюфта")
        advanceUntilIdle()
        assertTrue(vm.state.value.feed.any { it is FeedItem.NotFound })
    }

    // --- Съёмка: от кода до карточки — одно окно ---------------------------------------------

    /**
     * Поиск идёт до сети и может занять секунды. Раньше окно закрывалось сразу,
     * на это время мелькал дневник, а при промахе открывалось второе окно со своей
     * камерой. Теперь окно ждёт ответа открытым.
     */
    @Test
    fun `пока товар ищется по коду, окно съёмки открыто, а нашёлся — закрывается`() = runTest(dispatcher) {
        harness.userFoodDao.add(barcode = GTIN, name = "Сыр", kcal100 = 350)
        val network = CompletableDeferred<Unit>()
        harness.userFoodDao.slowLookup = network
        val vm = screen()

        vm.onOpenScan()
        vm.onScanned(GTIN)
        advanceUntilIdle()
        assertEquals(Overlay.Scan(ScanStep.Searching(GTIN)), vm.state.value.overlay)

        network.complete(Unit)
        advanceUntilIdle()
        assertEquals(Overlay.None, vm.state.value.overlay)
        assertEquals("Сыр ", vm.state.value.input)
    }

    @Test
    fun `кода нет в базе — то же окно переходит к карточке нового продукта`() = runTest(dispatcher) {
        val vm = screen()

        vm.onOpenScan()
        vm.onScanned(UNKNOWN_GTIN)
        advanceUntilIdle()

        assertEquals(Overlay.Scan(ScanStep.NewProduct(UNKNOWN_GTIN)), vm.state.value.overlay)
    }

    /** Человек передумал и закрыл окно, пока шёл поиск, — промах не открывает его снова. */
    @Test
    fun `окно закрыли посреди поиска — карточка нового продукта сама не открывается`() = runTest(dispatcher) {
        val network = CompletableDeferred<Unit>()
        harness.userFoodDao.slowLookup = network
        val vm = screen()

        vm.onOpenScan()
        vm.onScanned(UNKNOWN_GTIN)
        advanceUntilIdle()
        vm.onDismissOverlay()
        network.complete(Unit)
        advanceUntilIdle()

        assertEquals(Overlay.None, vm.state.value.overlay)
    }

    /** У развесного и домашнего кода нет — карточка открывается сразу, в том же окне съёмки. */
    @Test
    fun `съёмка этикетки открывает окно съёмки сразу с карточкой нового продукта`() = runTest(dispatcher) {
        val vm = screen()

        vm.onOpenLabelScan()
        advanceUntilIdle()

        assertEquals(Overlay.Scan(ScanStep.NewProduct(gtin = null)), vm.state.value.overlay)
    }

    private companion object {
        const val GTIN = "4607001771234"

        /** Код с верной контрольной цифрой, которого нет ни в одной базе. */
        const val UNKNOWN_GTIN = "4600000000008"
    }

    // --- Персонажи пишут и спрашивают -------------------------------------------------

    @Test
    fun `сообщение из шторки стоит в ленте своего дня`() = runTest(dispatcher) {
        harness.mascotDao.insert(harness.message(Mascot.GRAIN, "Уже три часа без еды!", minutesAgo = 20))
        val vm = screen()

        val said = vm.state.value.feed.filterIsInstance<FeedItem.Said>().single()
        assertEquals("Уже три часа без еды!", said.text)
        assertEquals(Mascot.GRAIN, said.mascot)
    }

    @Test
    fun `пустой день — имя ещё не спрашивают`() = runTest(dispatcher) {
        val vm = screen()

        assertTrue(vm.state.value.feed.none { it is FeedItem.Ask })
    }

    @Test
    fun `после первой записи Яйцо спрашивает имя`() = runTest(dispatcher) {
        harness.addEntry("Гречка", 200, MealType.BREAKFAST)
        val vm = screen()

        assertEquals(listOf(FeedItem.Ask(AskKind.NAME)), vm.state.value.feed.filterIsInstance<FeedItem.Ask>())
    }

    @Test
    fun `«Не скажу» убирает вопрос насовсем`() = runTest(dispatcher) {
        harness.addEntry("Гречка", 200, MealType.BREAKFAST)
        val vm = screen()

        vm.onAnswerName(null)
        advanceUntilIdle()

        assertTrue(vm.state.value.feed.none { it == FeedItem.Ask(AskKind.NAME) })
        assertEquals(true, harness.mascotDao.profileRow.value?.nameAsked)
    }

    @Test
    fun `имя сохраняется, и Яйцо отвечает в ленте`() = runTest(dispatcher) {
        harness.addEntry("Гречка", 200, MealType.BREAKFAST)
        val vm = screen()

        vm.onAnswerName("  Женя ")
        advanceUntilIdle()

        assertEquals("Женя", harness.mascotDao.profileRow.value?.name)
        val thanks = vm.state.value.feed.filterIsInstance<FeedItem.Said>().single()
        assertEquals(Mascot.EGG, thanks.mascot)
        assertTrue("Женя" in thanks.text, thanks.text)
    }

    @Test
    fun `после имени и двух записей Зёрнышко спрашивает, можно ли писать`() = runTest(dispatcher) {
        harness.mascotDao.upsertProfile(MascotProfileEntity(name = "Женя", nameAsked = true))
        harness.addEntry("Гречка", 200, MealType.BREAKFAST, minutesAgo = 60)
        val vm = screen()
        assertTrue(vm.state.value.feed.none { it is FeedItem.Ask }, "одна запись — ещё рано")

        harness.addEntry("Кефир", 250, MealType.BREAKFAST, minutesAgo = 10)
        advanceUntilIdle()

        assertEquals(listOf(FeedItem.Ask(AskKind.WRITE)), vm.state.value.feed.filterIsInstance<FeedItem.Ask>())
    }

    @Test
    fun `«Пишите» спрашивает разрешение телефона и отвечает по итогу`() = runTest(dispatcher) {
        readyToAskWrite()
        val vm = screen()
        val requests = mutableListOf<Unit>()
        backgroundScope.launch { vm.notificationRequests.collect { requests += it } }

        vm.onAnswerWrite(allow = true)
        advanceUntilIdle()

        assertEquals(1, requests.size)
        assertEquals(WriteConsent.ALLOWED, harness.mascotDao.profileRow.value?.consent)
        assertTrue(vm.state.value.feed.none { it is FeedItem.Ask })

        vm.onNotificationsResult(granted = true)
        advanceUntilIdle()

        assertEquals(Mascot.GRAIN, vm.state.value.feed.filterIsInstance<FeedItem.Said>().single().mascot)
    }

    @Test
    fun `«Не надо» — согласие отозвано, вопрос не возвращается`() = runTest(dispatcher) {
        readyToAskWrite()
        val vm = screen()

        vm.onAnswerWrite(allow = false)
        advanceUntilIdle()

        assertEquals(WriteConsent.DECLINED, harness.mascotDao.profileRow.value?.consent)
        assertTrue(vm.state.value.feed.none { it is FeedItem.Ask })
        assertEquals(Mascot.GRAIN, vm.state.value.feed.filterIsInstance<FeedItem.Said>().single().mascot)
    }

    /** Вернулся в приложение — всё, что персонажи писали, прочитано: ленту он видит. */
    @Test
    fun `возвращение в приложение отмечает сообщения прочитанными`() = runTest(dispatcher) {
        harness.mascotDao.insert(harness.message(Mascot.GRAIN, "Эй!"))
        val vm = screen()

        vm.onResume()
        advanceUntilIdle()

        assertTrue(harness.mascotDao.messages.value.all { it.openedAt != null })
        assertEquals(harness.clock.now().toEpochMilliseconds(), harness.mascotDao.profileRow.value?.lastOpenAt)
    }

    @Test
    fun `открыл из шторки — сообщение прочитано`() = runTest(dispatcher) {
        val id = harness.mascotDao.insert(harness.message(Mascot.BUTTER, "Мур."))
        val vm = screen()

        vm.onOpenedFromMessage(id)
        advanceUntilIdle()

        assertEquals(harness.clock.now().toEpochMilliseconds(), harness.mascotDao.findById(id)?.openedAt)
    }

    private suspend fun readyToAskWrite() {
        harness.mascotDao.upsertProfile(MascotProfileEntity(name = "Женя", nameAsked = true))
        harness.addEntry("Гречка", 200, MealType.BREAKFAST, minutesAgo = 60)
        harness.addEntry("Кефир", 250, MealType.BREAKFAST, minutesAgo = 10)
    }

    // --- Сцена: облачка и сигналы -------------------------------------------------------

    @Test
    fun `третий тык — облачко, и оно уходит само`() = runTest(dispatcher) {
        val vm = screen()

        vm.onMascotEvent(MascotEvent.Poked(Mascot.EGG, count = 3))
        runCurrent()

        val bubble = vm.state.value.speech.single()
        assertEquals(Mascot.EGG, bubble.mascot)
        assertTrue(bubble.text.isNotBlank())

        advanceUntilIdle()
        assertTrue(vm.state.value.speech.isEmpty(), "облачко живёт секунды")
    }

    @Test
    fun `у персонажа одно облачко — новое сменяет прошлое`() = runTest(dispatcher) {
        val vm = screen()

        vm.onMascotEvent(MascotEvent.Petted(Mascot.BUTTER))
        runCurrent()
        vm.onMascotEvent(MascotEvent.Petted(Mascot.BUTTER))
        runCurrent()

        assertEquals(1, vm.state.value.speech.count { it.mascot == Mascot.BUTTER })
    }

    @Test
    fun `запись еды — хозяин гордится`() = runTest(dispatcher) {
        val vm = screen()

        vm.onPickPrediction(harness.kefir)
        advanceUntilIdle()

        val cue = vm.state.value.cue
        assertEquals(CueKind.PROUD, cue?.kind)
        assertEquals(Mascot.GRAIN, cue?.mascot, "в кефире углеводов больше всего")
    }

    @Test
    fun `цель закрыта точно — праздник`() = runTest(dispatcher) {
        harness.goalDao.upsert(DailyGoalEntity(harness.today, kcal = 2_000, prot = 0, fat = 0, carb = 0))
        harness.addEntry("Обед", grams = 1_850, meal = MealType.LUNCH, kcal100 = 100)
        val vm = screen()

        vm.onPickPrediction(harness.kefir)
        advanceUntilIdle()

        assertEquals(CueKind.CELEBRATE, vm.state.value.cue?.kind)
    }

    @Test
    fun `вернулся через пять часов — встречают`() = runTest(dispatcher) {
        val fiveHoursAgo = harness.clock.now().toEpochMilliseconds() - 5 * 60 * 60 * 1000L
        harness.mascotDao.upsertProfile(MascotProfileEntity(lastOpenAt = fiveHoursAgo))
        val vm = screen()

        vm.onResume()
        advanceUntilIdle()

        assertEquals(CueKind.GREET, vm.state.value.cue?.kind)
    }

    @Test
    fun `открыл из шторки — автор радуется`() = runTest(dispatcher) {
        val id = harness.mascotDao.insert(harness.message(Mascot.BUTTER, "Мур."))
        val vm = screen()

        vm.onOpenedFromMessage(id)
        advanceUntilIdle()

        assertEquals(CueKind.OPENED, vm.state.value.cue?.kind)
        assertEquals(Mascot.BUTTER, vm.state.value.cue?.mascot)
    }

    @Test
    fun `тряска — у всех кружится голова`() = runTest(dispatcher) {
        val vm = screen()

        vm.onPhoneShaken()
        advanceUntilIdle()

        assertEquals(CueKind.DIZZY, vm.state.value.cue?.kind)
        assertEquals(null, vm.state.value.cue?.mascot)
    }

    @Test
    fun `ночью персонажи спят, утром — нет`() = runTest(dispatcher) {
        assertFalse(screen().state.value.night, "в девять утра")

        val night = DiaryHarness(now = "2026-10-31T02:00:00Z")
        val vm = night.viewModel(dispatcher).also { backgroundScope.launch { it.state.collect {} } }
        advanceUntilIdle()

        assertTrue(vm.state.value.night)
    }
}
