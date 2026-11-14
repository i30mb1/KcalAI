package n7.kcalai.feature.diary

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import n7.kcalai.assistant.FoodAssistant
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Nutriments
import n7.kcalai.resolver.ResolvedItem

/**
 * Нейросеть в дневнике: где правила не разобрали фразу — и где человек
 * спрашивает, а не записывает.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantTest {

    private val dispatcher = StandardTestDispatcher()
    private val harness = DiaryHarness()
    private val assistant = FakeAssistant()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.screen(withAssistant: Boolean = true): DiaryViewModel =
        harness.viewModel(dispatcher, assistant = assistant.takeIf { withAssistant }).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            advanceUntilIdle()
        }

    private val DiaryViewModel.notFound: FeedItem.NotFound?
        get() = state.value.feed.filterIsInstance<FeedItem.NotFound>().singleOrNull()

    private val borscht = FoodCandidate(
        ref = FoodRef.Generic(12),
        displayName = "Борщ",
        nutriments = Nutriments(kcal100 = 49, prot100 = 110, fat100 = 220, carb100 = 670),
        servingG = 300,
    )

    private val kefir = FoodCandidate(
        ref = FoodRef.Generic(303),
        displayName = "Кефир",
        nutriments = Nutriments(kcal100 = 40, prot100 = 300, fat100 = 100, carb100 = 400),
        servingG = 250,
    )

    private fun proposal(candidate: FoodCandidate, grams: Int, phrase: String) = ResolvedItem(
        sourceText = phrase,
        candidate = candidate,
        grams = grams,
        confidence = 0.6f,
        source = EntrySource.LLM,
        gramsGuessed = true,
    )

    // --- Фраза, которую не разобрал поиск ------------------------------------------------

    @Test
    fun `без нейросети «не нашлось» её не предлагает`() = runTest(dispatcher) {
        val vm = screen(withAssistant = false)

        vm.onInputChange("доел за ребёнком макароны")
        advanceUntilIdle()

        assertEquals(Assist.NONE, vm.notFound?.assist)
    }

    @Test
    fun `на телефоне без модели «не нашлось» её не предлагает`() = runTest(dispatcher) {
        assistant.isReady = false
        val vm = screen()

        vm.onInputChange("доел за ребёнком макароны")
        advanceUntilIdle()

        assertEquals(Assist.NONE, vm.notFound?.assist)
    }

    @Test
    fun `с нейросетью «не нашлось» предлагает разобрать фразу`() = runTest(dispatcher) {
        val vm = screen()

        vm.onInputChange("доел за ребёнком макароны")
        advanceUntilIdle()

        assertEquals(Assist.OFFER, vm.notFound?.assist)
    }

    @Test
    fun `предложения нейросети встают чипсами над строкой ввода`() = runTest(dispatcher) {
        assistant.proposals = listOf(
            proposal(borscht, 350, "тарелка борща"),
            proposal(harness.kefir, 250, "стакан кефира"),
        )
        assistant.gate = CompletableDeferred()
        val vm = screen()
        vm.onInputChange("тарелка борща и стакан кефира")
        advanceUntilIdle()

        vm.onAskAssistant("тарелка борща и стакан кефира")
        advanceUntilIdle()
        // Модель думает секунды — реплика это показывает, а не молчит.
        assertEquals(Assist.THINKING, vm.notFound?.assist)

        assistant.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("тарелка борща и стакан кефира"), assistant.asked)
        val row = assertIs<ComposerRow.Results>(vm.state.value.composer)
        assertEquals(listOf("Борщ", "Кефир 1%"), row.items.map { it.candidate?.displayName })
        assertEquals(null, vm.notFound)
    }

    @Test
    fun `чипс нейросети записывается а остальные её предложения остаются`() = runTest(dispatcher) {
        assistant.proposals = listOf(
            proposal(borscht, 350, "тарелка борща"),
            proposal(harness.kefir, 250, "стакан кефира"),
        )
        val vm = screen()
        val phrase = "тарелка борща и стакан кефира"
        vm.onInputChange(phrase)
        advanceUntilIdle()
        vm.onAskAssistant(phrase)
        advanceUntilIdle()

        val first = assertIs<ComposerRow.Results>(vm.state.value.composer).items.first()
        vm.onPick(first)
        advanceUntilIdle()

        val logged = harness.diaryDao.day(harness.today).single()
        assertEquals("Борщ", logged.displayName)
        assertEquals(350, logged.grams)
        assertEquals(EntrySource.LLM, logged.source)
        // Кефир из той же фразы ждёт своего тапа.
        assertEquals(phrase, vm.state.value.input)
        val left = assertIs<ComposerRow.Results>(vm.state.value.composer).items
        assertEquals(listOf("Кефир 1%"), left.map { it.candidate?.displayName })

        vm.onPick(left.single())
        advanceUntilIdle()

        assertEquals(2, harness.diaryDao.day(harness.today).size)
        assertEquals("", vm.state.value.input)
        assertIs<ComposerRow.None>(vm.state.value.composer)
    }

    @Test
    fun `нейросеть тоже не разобрала — реплика говорит об этом и больше не предлагает`() = runTest(dispatcher) {
        val vm = screen()
        vm.onInputChange("хрючево")
        advanceUntilIdle()

        vm.onAskAssistant("хрючево")
        advanceUntilIdle()

        assertEquals(Assist.FAILED, vm.notFound?.assist)
    }

    @Test
    fun `сбой модели — та же реплика — не падение экрана`() = runTest(dispatcher) {
        assistant.failure = IllegalStateException("BUSY")
        val vm = screen()
        vm.onInputChange("хрючево")
        advanceUntilIdle()

        vm.onAskAssistant("хрючево")
        advanceUntilIdle()

        assertEquals(Assist.FAILED, vm.notFound?.assist)
    }

    @Test
    fun `ответ на стёртую строку не встаёт чипсами`() = runTest(dispatcher) {
        assistant.proposals = listOf(proposal(borscht, 350, "борщ"))
        assistant.gate = CompletableDeferred()
        val vm = screen()
        vm.onInputChange("тарелка борща")
        advanceUntilIdle()
        vm.onAskAssistant("тарелка борща")
        advanceUntilIdle()

        vm.onInputChange("")
        advanceUntilIdle()
        assistant.gate!!.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.state.value.composer is ComposerRow.Results)
    }

    // --- Вопрос про дневник --------------------------------------------------------------

    @Test
    fun `вопрос уходит нейросети — не в поиск и ответ встаёт в ленту`() = runTest(dispatcher) {
        assistant.answerParts = listOf("Вчера ", "Вчера 1840 ккал.")
        val vm = screen()

        vm.onInputChange("сколько я съел вчера?")
        advanceUntilIdle()
        // Вопрос — не блюдо: «ничего не нашлось» на него было бы глупостью.
        assertEquals(null, vm.notFound)

        assertTrue(vm.onSubmit())
        advanceUntilIdle()

        assertEquals("", vm.state.value.input)
        assertEquals(listOf("сколько я съел вчера?"), assistant.asked)
        val feed = vm.state.value.feed
        val question = feed.filterIsInstance<FeedItem.Question>().single()
        assertEquals("сколько я съел вчера?", question.text)
        val answer = feed.filterIsInstance<FeedItem.Answer>().single()
        assertEquals("Вчера 1840 ккал.", answer.text)
        assertTrue(answer.done)
        assertTrue(feed.indexOf(question) < feed.indexOf(answer))
    }

    @Test
    fun `без нейросети вопрос — обычный поиск`() = runTest(dispatcher) {
        val vm = screen(withAssistant = false)

        vm.onInputChange("сколько я съел вчера?")
        advanceUntilIdle()

        assertFalse(vm.onSubmit())
        assertTrue(vm.state.value.feed.none { it is FeedItem.Question })
    }

    @Test
    fun `модель не ответила — ответ говорит об этом`() = runTest(dispatcher) {
        assistant.answerParts = emptyList()
        assistant.failure = IllegalStateException("BUSY")
        val vm = screen()

        vm.onInputChange("сколько белка за неделю?")
        advanceUntilIdle()
        vm.onSubmit()
        advanceUntilIdle()

        val answer = vm.state.value.feed.filterIsInstance<FeedItem.Answer>().single()
        assertTrue(answer.done)
        assertTrue(answer.text.startsWith("Не получилось ответить"), answer.text)
    }

    // --- Кнопка «отправить» у строки ввода ---------------------------------------------

    /** Кнопка показывает «отправить», и ✓ — ею или на клавиатуре — отдаёт фразу на разбор. */
    @Test
    fun `с нейросетью ✓ отправляет фразу на разбор`() = runTest(dispatcher) {
        val vm = screen()
        vm.onInputChange("доел за ребёнком макароны")
        advanceUntilIdle()
        assertEquals(ComposerAction.RECORD, vm.state.value.action)

        assertTrue(vm.onSubmit())
        advanceUntilIdle()

        assertEquals(listOf("доел за ребёнком макароны"), assistant.asked)
    }

    @Test
    fun `без нейросети кнопка та же но записывать нечего — она только прячет клавиатуру`() = runTest(dispatcher) {
        val vm = screen(withAssistant = false)
        vm.onInputChange("доел за ребёнком макароны")
        advanceUntilIdle()

        assertEquals(ComposerAction.RECORD, vm.state.value.action)
        assertFalse(vm.onSubmit())
    }

    /** Кнопка не меняется, пока стоит ответ нейросети: чипсы записываются ею по одному. */
    @Test
    fun `ответ нейросети записывается по ✓ блюдо за блюдом`() = runTest(dispatcher) {
        assistant.proposals = listOf(proposal(borscht, 300, "тарелка борща"), proposal(kefir, 200, "стакан кефира"))
        val vm = screen()
        vm.onInputChange("тарелка борща и стакан кефира")
        advanceUntilIdle()
        vm.onSubmit()
        advanceUntilIdle()
        assertEquals(ComposerAction.RECORD, vm.state.value.action)

        assertTrue(vm.onSubmit())
        advanceUntilIdle()
        assertEquals(listOf("Борщ"), harness.diaryDao.day(harness.today).map { it.displayName })
        assertEquals("тарелка борща и стакан кефира", vm.state.value.input)

        assertTrue(vm.onSubmit())
        advanceUntilIdle()
        assertEquals(listOf("Борщ", "Кефир"), harness.diaryDao.day(harness.today).map { it.displayName })
        assertEquals("", vm.state.value.input)
        assertEquals(1, assistant.asked.size)
    }

    /** Нейросеть второй раз не спрашиваем, а выдача поиска никуда не делась. */
    @Test
    fun `нейросеть не разобрала — ✓ берёт первый вариант поиска`() = runTest(dispatcher) {
        repeat(6) { i -> harness.userFoodDao.add(barcode = null, name = "Гречка $i", kcal100 = 110) }
        val vm = screen()
        vm.onInputChange("гречка 200")
        advanceUntilIdle()
        vm.onSubmit()
        advanceUntilIdle()
        assertEquals(listOf("гречка 200"), assistant.asked)
        assertTrue(harness.diaryDao.day(harness.today).isEmpty())

        assertTrue(vm.onSubmit())
        advanceUntilIdle()

        assertEquals(listOf(200), harness.diaryDao.day(harness.today).map { it.grams })
        assertEquals(1, assistant.asked.size)
    }
}

/** Модель, которая отвечает тем, что ей велели, и тогда, когда ей велели. */
internal class FakeAssistant : FoodAssistant {
    var isReady = true
    var proposals: List<ResolvedItem> = emptyList()
    var answerParts: List<String> = listOf("Ответ.")
    var failure: Exception? = null

    /** `null` — отвечает сразу; иначе разбор фразы ждёт этого сигнала. */
    var gate: CompletableDeferred<Unit>? = null

    val asked = mutableListOf<String>()

    override suspend fun ready(): Boolean = isReady

    override suspend fun propose(text: String): List<ResolvedItem> {
        asked += text
        gate?.await()
        failure?.let { throw it }
        return proposals
    }

    override fun answer(question: String): Flow<String> = flow {
        asked += question
        answerParts.forEach { emit(it) }
        failure?.let { throw it }
    }
}
