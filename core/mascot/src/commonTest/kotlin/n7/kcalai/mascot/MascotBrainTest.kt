package n7.kcalai.mascot

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import n7.kcalai.database.Choice
import n7.kcalai.database.Delivery
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.MascotMessageEntity
import n7.kcalai.database.MascotProfileEntity
import n7.kcalai.database.WriteConsent
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.BackgroundModel
import n7.kcalai.model.EntrySource
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.ModelDelivery
import n7.kcalai.personal.PersonalRepository

/**
 * Мозг персонажей: пишет первым, отвечает на ответы и реагирует на касания и еду.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MascotBrainTest {

    private val zone = TimeZone.UTC
    private val diary = FakeDiaryDao()
    private val goals = FakeGoalDao()
    private val mascots = FakeMascotDao()

    private fun brain(zone: TimeZone = this.zone) = MascotBrain(
        dao = mascots,
        facts = FactsBuilder(diary, goals, PersonalRepository(diary, FakePersonalDao(), FakeBodyMetricDao(), goals, zone), zone),
        diaryDao = diary,
        zone = zone,
        random = Random(1),
    )

    /** Пятница, 16:00. */
    private val now = Instant.parse("2026-11-06T16:00:00Z")
    private val today = LocalDate(2026, 11, 6).toEpochDays()

    private suspend fun allow() = mascots.upsertProfile(MascotProfileEntity(consent = WriteConsent.ALLOWED))

    private suspend fun eat(name: String, at: Instant, prot100: Int = 420, carb100: Int = 2_130): DiaryEntryEntity {
        val entry = DiaryEntryEntity(
            dateEpochDay = at.toEpochMilliseconds() / DAY_MS,
            meal = MealType.LUNCH,
            displayName = name,
            grams = 200,
            kcal100 = 110,
            prot100 = prot100,
            fat100 = 110,
            carb100 = carb100,
            source = EntrySource.TEXT,
            foodRef = "generic:${name.hashCode() and 0xffff}",
            createdAt = at.toEpochMilliseconds(),
        )
        return entry.copy(id = diary.insert(entry))
    }

    // --- Пишет первым ---------------------------------------------------------------------

    @Test
    fun `без согласия не пишет`() = runTest {
        eat("Гречка", now - 4.hours)

        assertNull(brain().write(now, notificationsEnabled = true))
        mascots.upsertProfile(MascotProfileEntity(consent = WriteConsent.DECLINED))
        assertNull(brain().write(now, notificationsEnabled = true))
    }

    @Test
    fun `при выключенных уведомлениях не пишет и не тратит бюджет`() = runTest {
        allow()
        eat("Гречка", now - 4.hours)

        assertNull(brain().write(now, notificationsEnabled = false))
        assertTrue(mascots.messages.value.isEmpty())
    }

    @Test
    fun `пишет по поводу — и кладёт в шторку`() = runTest {
        allow()
        eat("Гречка", now - 4.hours)

        val written = brain().write(now, notificationsEnabled = true)

        assertNotNull(written)
        assertEquals(Topic.LONG_GAP.name, written.message.topic)
        assertEquals(Delivery.NOTIFICATION, written.message.delivery)
        assertEquals(today, written.message.dateEpochDay)
        assertEquals(listOf(written.message), mascots.messages.value)
        assertEquals(listOf(written.message), written.thread)
    }

    @Test
    fun `написанное ложится в день по местной зоне`() = runTest {
        // 21:30 по UTC — это уже 00:30 субботы в Москве.
        val lateUtc = Instant.parse("2026-11-06T21:30:00Z")

        val written = brain(TimeZone.of("Europe/Moscow")).write(Topic.EMPTY_DAY, Mascot.EGG, lateUtc)

        assertEquals(LocalDate(2026, 11, 7).toEpochDays(), written?.message?.dateEpochDay)
    }

    @Test
    fun `второе сообщение не повторяет варианты первого`() = runTest {
        eat("Гречка", now - 4.days)
        val brain = brain()

        val first = brain.write(Topic.COMEBACK, Mascot.GRAIN, now)
        val second = brain.write(Topic.COMEBACK, Mascot.GRAIN, now + 1.hours)

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first.message.text, second.message.text)
        assertTrue((first.message.lineIds.split(',') intersect second.message.lineIds.split(',').toSet()).isEmpty())
    }

    // --- Возвращение ----------------------------------------------------------------------

    @Test
    fun `открытие приложения отмечает прочитанным и помнит время`() = runTest {
        mascots.insert(message(Mascot.GRAIN))

        brain().onAppOpened(now)

        assertTrue(mascots.messages.value.all { it.openedAt == now.toEpochMilliseconds() })
        assertEquals(now.toEpochMilliseconds(), mascots.profileRow.value?.lastOpenAt)
    }

    @Test
    fun `вернулся через пять часов — встречают`() = runTest {
        mascots.upsertProfile(MascotProfileEntity(lastOpenAt = (now - 5.hours).toEpochMilliseconds()))

        val reaction = brain().onAppOpened(now)

        assertEquals(CueKind.GREET, reaction?.cue?.kind)
        assertNotNull(reaction?.speech)
    }

    @Test
    fun `час назад — не встречают, ночью — тем более`() = runTest {
        mascots.upsertProfile(MascotProfileEntity(lastOpenAt = (now - 1.hours).toEpochMilliseconds()))
        assertNull(brain().onAppOpened(now))

        val night = Instant.parse("2026-11-07T02:00:00Z")
        mascots.upsertProfile(MascotProfileEntity(lastOpenAt = (night - 9.hours).toEpochMilliseconds()))
        assertNull(brain().onAppOpened(night))
    }

    // --- Реакции --------------------------------------------------------------------------

    @Test
    fun `третий тык — реплика, первый и второй — молча`() = runTest {
        val brain = brain()

        assertNull(brain.react(MascotEvent.Poked(Mascot.EGG, 1), now)?.speech)
        assertNull(brain.react(MascotEvent.Poked(Mascot.EGG, 2), now)?.speech)
        assertEquals(Mascot.EGG, brain.react(MascotEvent.Poked(Mascot.EGG, 3), now)?.speech?.mascot)
    }

    @Test
    fun `еда — гордится хозяин, новая — с репликой`() = runTest {
        mascots.upsertProfile(MascotProfileEntity(name = "Женя", nameAsked = true))
        val curd = eat("Творог", now, prot100 = 1_700, carb100 = 300)

        val reaction = brain().react(MascotEvent.Ate(curd), now)

        assertEquals(Cue(CueKind.PROUD, Mascot.EGG), reaction?.cue)
        assertEquals(Mascot.EGG, reaction?.speech?.mascot)
    }

    @Test
    fun `открыл из шторки — отмечено прочитанным, автор радуется`() = runTest {
        val id = mascots.insert(message(Mascot.BUTTER))

        val reaction = brain().react(MascotEvent.Opened(id), now)

        assertEquals(now.toEpochMilliseconds(), mascots.findById(id)?.openedAt)
        assertEquals(Cue(CueKind.OPENED, Mascot.BUTTER), reaction?.cue)
        assertEquals(Mascot.BUTTER, reaction?.speech?.mascot)
    }

    /** «Записать» в шторке — это ответ: сообщение прочитано, и в переписке оно стоит первым. */
    @Test
    fun `ответ из шторки — сообщение прочитано и находится`() = runTest {
        val id = mascots.insert(message(Mascot.GRAIN))
        val brain = brain()

        brain.markOpened(id, now)

        assertEquals(now.toEpochMilliseconds(), brain.message(id)?.openedAt)
    }

    @Test
    fun `тряска — не чаще раза в десять секунд`() = runTest {
        val brain = brain()

        assertEquals(CueKind.DIZZY, brain.react(MascotEvent.Shaken, now)?.cue?.kind)
        assertNull(brain.react(MascotEvent.Shaken, now + 5.seconds))
        assertEquals(CueKind.DIZZY, brain.react(MascotEvent.Shaken, now + 11.seconds)?.cue?.kind)
    }

    // --- Вопросы: по одному ---------------------------------------------------------------

    /** Персонажи говорят по одному: на запись — либо вопрос, либо облачко, а не оба разом. */
    @Test
    fun `на первую запись Яйцо спрашивает имя — вопросом в ленте и без облачка`() = runTest {
        val buckwheat = eat("Гречка", now)

        val reaction = brain().react(MascotEvent.Ate(buckwheat), now)

        assertNull(reaction?.speech, "облачко молчит, пока звучит вопрос")
        assertEquals(Cue(CueKind.PROUD, Mascot.GRAIN), reaction?.cue, "хозяин еды всё равно гордится")
        val asked = mascots.messages.value.single()
        assertEquals(MascotQuestion.NAME, asked.question)
        assertEquals(Mascot.EGG, asked.mascot)
        assertEquals(Delivery.FEED, asked.delivery)
        assertEquals(today, asked.dateEpochDay)
        assertNull(asked.choice)
    }

    @Test
    fun `без ответа вопрос не повторяют в тот же день — только назавтра`() = runTest {
        val brain = brain()

        brain.react(MascotEvent.Ate(eat("Гречка", now)), now)
        val second = brain.react(MascotEvent.Ate(eat("Кефир", now + 1.hours)), now + 1.hours)

        assertEquals(1, mascots.messages.value.count { it.question == MascotQuestion.NAME })
        assertNotNull(second?.speech, "вопрос уже задан — на запись снова отвечает облачко")

        brain.react(MascotEvent.Ate(eat("Гречка", now + 1.days)), now + 1.days)
        assertEquals(2, mascots.messages.value.count { it.question == MascotQuestion.NAME })
    }

    /** Ответ на имя — один ответ Яйца. Второй вопрос вслед за ним был бы двумя репликами разом. */
    @Test
    fun `разрешение писать спрашивают со следующей записью, а не вслед за ответом на имя`() = runTest {
        val brain = brain()
        brain.react(MascotEvent.Ate(eat("Гречка", now - 2.hours)), now - 2.hours)
        brain.react(MascotEvent.Ate(eat("Кефир", now - 1.hours)), now - 1.hours)

        brain.answerName("Женя", now)

        assertTrue(mascots.messages.value.none { it.question == MascotQuestion.WRITE })

        val reaction = brain.react(MascotEvent.Ate(eat("Сыр", now + 1.hours)), now + 1.hours)

        assertEquals(MascotQuestion.WRITE, mascots.messages.value.last().question)
        assertEquals(Mascot.GRAIN, mascots.messages.value.last().mascot)
        assertNull(reaction?.speech)
    }

    @Test
    fun `на прошлый день не спрашивают`() = runTest {
        val yesterday = eat("Гречка", now - 1.days)

        brain().react(MascotEvent.Ate(yesterday), now)

        assertTrue(mascots.messages.value.none { it.question != null })
    }

    @Test
    fun `ответ ложится в сам вопрос, и второй раз его не принимают`() = runTest {
        val brain = brain()
        brain.react(MascotEvent.Ate(eat("Гречка", now)), now)

        assertTrue(brain.answerName(null, now))
        assertEquals(Choice.NO, mascots.messages.value.single { it.question == MascotQuestion.NAME }.choice)

        assertFalse(brain.answerName("Женя", now), "на отвеченный вопрос второй раз не отвечают")
        assertNull(mascots.profileRow.value?.name)
        assertTrue(mascots.messages.value.none { it.topic == Topic.NAME_THANKS.name })
    }

    @Test
    fun `вопрос держит слово, пока он после последней записи и без ответа`() = runTest {
        val brain = brain()
        val entry = eat("Гречка", now)
        brain.react(MascotEvent.Ate(entry), now)
        val said = mascots.messages.value

        assertTrue(awaitsAnswer(said, lastEntryAt = entry.createdAt))
        assertFalse(awaitsAnswer(said, lastEntryAt = (now + 1.minutes).toEpochMilliseconds()), "записал дальше, не ответив")
        brain.answerName(null, now)
        assertFalse(awaitsAnswer(mascots.messages.value, lastEntryAt = entry.createdAt))
    }

    // --- Фоновая докачка ------------------------------------------------------------------

    /** Новость о фоновой работе не перебивает знакомство: до первой записи на экране только оно. */
    @Test
    fun `новость ждёт, пока человек не начнёт вести дневник`() = runTest {
        val brain = brain()

        val said = async { brain.report(BackgroundModel.SEARCH, ModelDelivery.READY, at(now)) }
        advanceTimeBy(1.days)
        runCurrent()
        assertTrue(mascots.messages.value.isEmpty())

        eat("Гречка", now)
        advanceUntilIdle()

        assertEquals(Topic.SEARCH_READY.name, said.await()?.topic)
    }

    @Test
    fun `пока вопрос ждёт ответа, новость ждёт тоже`() = runTest {
        val brain = brain()
        brain.react(MascotEvent.Ate(eat("Гречка", now - 10.minutes)), now - 10.minutes)

        val said = async { brain.report(BackgroundModel.SEARCH, ModelDelivery.READY, at(now)) }
        advanceUntilIdle()
        assertFalse(said.isCompleted)

        brain.answerName(null, now)
        advanceUntilIdle()

        assertEquals(Topic.SEARCH_READY.name, said.await()?.topic)
    }

    /** Ответ Яйца на имя и новость о поиске в одну секунду — два сообщения разом. */
    @Test
    fun `новость не приходит вслед за другим сообщением — сначала пауза`() = runTest {
        started()
        mascots.insert(message(Mascot.EGG).copy(createdAt = now.toEpochMilliseconds(), delivery = Delivery.FEED))
        val before = testScheduler.currentTime

        val said = brain().report(BackgroundModel.SEARCH, ModelDelivery.READY, at(now))

        assertNotNull(said)
        assertTrue(testScheduler.currentTime - before >= 60_000, "ждали ${testScheduler.currentTime - before} мс")
    }

    @Test
    fun `говорить не о чем — очереди не ждут`() = runTest {
        started()
        brain().report(BackgroundModel.SEARCH, ModelDelivery.READY, at(now))
        val before = testScheduler.currentTime

        assertNull(brain().report(BackgroundModel.SEARCH, ModelDelivery.READY, at(now)))
        assertEquals(before, testScheduler.currentTime)
    }

    /** Сообщение попадает в ленту дня, а не в шторку: оно не тратит бюджет уведомлений. */
    @Test
    fun `докачку умного поиска Яйцо докладывает в ленту`() = runTest {
        started()
        val said = brain().report(BackgroundModel.SEARCH, ModelDelivery.DOWNLOADING, at(now))

        assertEquals(Topic.SEARCH_LOADING.name, said?.topic)
        assertEquals(Mascot.EGG, said?.mascot)
        assertEquals(Delivery.FEED, said?.delivery)
        assertEquals(today, said?.dateEpochDay)
    }

    @Test
    fun `кто о чём сообщает`() = runTest {
        started()
        val brain = brain()

        assertEquals(Mascot.BUTTER, brain.report(BackgroundModel.SEARCH, ModelDelivery.WAITING_FOR_WIFI, at(now))?.mascot)
        assertEquals(Mascot.BUTTER, brain.report(BackgroundModel.SEARCH, ModelDelivery.FAILED, at(now))?.mascot)
        assertEquals(Mascot.GRAIN, brain.report(BackgroundModel.SEARCH, ModelDelivery.READY, at(now))?.mascot)
        assertEquals(
            Topic.ASSISTANT_READY.name,
            brain.report(BackgroundModel.ASSISTANT, ModelDelivery.READY, at(now))?.topic,
        )
    }

    /** Статус приходит при каждом запуске, а рассказать надо один раз. */
    @Test
    fun `о начале докачки сообщают один раз, и после перезапуска тоже`() = runTest {
        started()
        brain().report(BackgroundModel.SEARCH, ModelDelivery.DOWNLOADING, at(now))

        assertNull(brain().report(BackgroundModel.SEARCH, ModelDelivery.DOWNLOADING, at(now + 2.days)))
    }

    @Test
    fun `о том, что докачалось, сообщают один раз`() = runTest {
        started()
        brain().report(BackgroundModel.SEARCH, ModelDelivery.READY, at(now))

        assertNull(brain().report(BackgroundModel.SEARCH, ModelDelivery.READY, at(now + 2.days)))
    }

    /** Обновление приложения докачивает пак заново, но человек уже всё знает. */
    @Test
    fun `докачанное не начинает разговор заново`() = runTest {
        started()
        brain().report(BackgroundModel.SEARCH, ModelDelivery.READY, at(now))

        assertNull(brain().report(BackgroundModel.SEARCH, ModelDelivery.DOWNLOADING, at(now + 2.days)))
        assertNull(brain().report(BackgroundModel.SEARCH, ModelDelivery.WAITING_FOR_WIFI, at(now + 2.days)))
        assertNull(brain().report(BackgroundModel.SEARCH, ModelDelivery.FAILED, at(now + 2.days)))
    }

    /** Сбой может повторяться часами, и каждый раз жаловаться незачем. */
    @Test
    fun `о сбое говорят не чаще раза в день`() = runTest {
        started()
        brain().report(BackgroundModel.SEARCH, ModelDelivery.FAILED, at(now))

        assertNull(brain().report(BackgroundModel.SEARCH, ModelDelivery.FAILED, at(now + 3.hours)))
        assertNotNull(brain().report(BackgroundModel.SEARCH, ModelDelivery.FAILED, at(now + 1.days)))
    }

    @Test
    fun `поиск и нейросеть докачиваются и обсуждаются порознь`() = runTest {
        started()
        brain().report(BackgroundModel.SEARCH, ModelDelivery.READY, at(now))

        assertEquals(
            Topic.ASSISTANT_LOADING.name,
            brain().report(BackgroundModel.ASSISTANT, ModelDelivery.DOWNLOADING, at(now))?.topic,
        )
    }

    /** Нейросеть качает система, и ждать Wi-Fi там не о чем: своей такой темы у неё нет. */
    @Test
    fun `нейросеть про Wi-Fi не говорит`() = runTest {
        started()
        assertNull(brain().report(BackgroundModel.ASSISTANT, ModelDelivery.WAITING_FOR_WIFI, at(now)))
    }

    @Test
    fun `ответ на имя — по имени и в ленту`() = runTest {
        asked(MascotQuestion.NAME)

        assertTrue(brain().answerName("Женя", now))

        assertEquals("Женя", mascots.profileRow.value?.name)
        assertEquals(true, mascots.profileRow.value?.nameAsked)
        assertEquals(Choice.YES, mascots.messages.value.single { it.question == MascotQuestion.NAME }.choice)
        val thanks = mascots.messages.value.single { it.topic == Topic.NAME_THANKS.name }
        assertTrue("Женя" in thanks.text, thanks.text)
        assertEquals(Mascot.EGG, thanks.mascot)
        assertEquals(Delivery.FEED, thanks.delivery)
    }

    @Test
    fun `«Не скажу» — второй раз не спрашивают`() = runTest {
        asked(MascotQuestion.NAME)

        assertTrue(brain().answerName(null, now))

        assertEquals(true, mascots.profileRow.value?.nameAsked)
        assertNull(mascots.profileRow.value?.name)
        assertEquals(Choice.NO, mascots.messages.value.single().choice, "Яйцо не благодарит — не за что")
    }

    @Test
    fun `«Пишите» ждёт ответа телефона, а Зёрнышко отвечает по итогу`() = runTest {
        asked(MascotQuestion.WRITE)
        val brain = brain()

        assertTrue(brain.answerWrite(allow = true, now))
        assertEquals(WriteConsent.ALLOWED, mascots.profileRow.value?.consent)
        assertEquals(Choice.YES, mascots.messages.value.single().choice, "ответит телефон — тогда и Зёрнышко")
        assertEquals(Topic.WRITE_YES.name, brain.notificationsResult(granted = true, now)?.topic)
        assertEquals(Topic.WRITE_DENIED.name, brain.notificationsResult(granted = false, now)?.topic)
    }

    @Test
    fun `«Не надо» — согласие отозвано, Зёрнышко обещает молчать`() = runTest {
        asked(MascotQuestion.WRITE)

        assertTrue(brain().answerWrite(allow = false, now))

        assertEquals(WriteConsent.DECLINED, mascots.profileRow.value?.consent)
        val reply = mascots.messages.value.last()
        assertEquals(Topic.WRITE_NO.name, reply.topic)
        assertEquals(Mascot.GRAIN, reply.mascot)
    }

    /** Повторный тап по «Пишите» не должен второй раз спрашивать телефон. */
    @Test
    fun `на отвеченный вопрос про сообщения второй ответ не принимается`() = runTest {
        asked(MascotQuestion.WRITE)
        val brain = brain()
        brain.answerWrite(allow = true, now)

        assertFalse(brain.answerWrite(allow = false, now + 1.minutes))
        assertEquals(WriteConsent.ALLOWED, mascots.profileRow.value?.consent)
        assertEquals(Choice.YES, mascots.messages.value.single().choice)
    }

    @Test
    fun `«Не надо» переспрашивают через месяц`() {
        fun declined(ago: kotlin.time.Duration) =
            MascotProfileEntity(consent = WriteConsent.DECLINED, consentAt = (now - ago).toEpochMilliseconds())

        assertTrue(MascotProfileEntity().asksToWrite(now))
        assertFalse(MascotProfileEntity(consent = WriteConsent.ALLOWED).asksToWrite(now))
        assertFalse(declined(10.days).asksToWrite(now))
        assertTrue(declined(31.days).asksToWrite(now))
    }

    /** Часы, стоящие на [instant]: паузу между сообщениями отсчитывает виртуальное время теста. */
    private fun at(instant: Instant): Clock = object : Clock {
        override fun now(): Instant = instant
    }

    /** Человек уже ведёт дневник: вчера была запись, и знакомство позади. */
    private suspend fun started() {
        eat("Гречка", now - 1.days)
    }

    /** Вопрос, заданный минуту назад и ждущий ответа. */
    private suspend fun asked(question: MascotQuestion) = mascots.insert(
        MascotMessageEntity(
            mascot = question.mascot,
            topic = question.topic,
            text = question.text,
            lineIds = "",
            delivery = Delivery.FEED,
            dateEpochDay = today,
            createdAt = (now - 1.minutes).toEpochMilliseconds(),
        )
    )

    private fun message(mascot: Mascot) = MascotMessageEntity(
        mascot = mascot,
        topic = Topic.LONG_GAP.name,
        text = "Уже три часа без еды.",
        lineIds = "",
        delivery = Delivery.NOTIFICATION,
        dateEpochDay = today,
        createdAt = (now - 2.hours).toEpochMilliseconds(),
    )

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
