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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import n7.kcalai.database.Choice
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.Delivery
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.MascotMessageEntity
import n7.kcalai.database.MascotProfileEntity
import n7.kcalai.database.WriteConsent
import n7.kcalai.database.heightOrDefault
import n7.kcalai.database.stepsGoalOrDefault
import n7.kcalai.model.DEFAULT_HEIGHT_CM
import n7.kcalai.model.DEFAULT_STEPS_GOAL
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.BackgroundModel
import n7.kcalai.model.EntrySource
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.ModelDelivery
import n7.kcalai.model.PageGrace
import n7.kcalai.model.Walk
import n7.kcalai.model.WalkTotals
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

    /** Когда пришли просьбы, ждущие ответа: браузер просится в дневник, сосед предлагает еду. */
    private val asking = MutableStateFlow<List<Long>>(emptyList())

    private fun brain(zone: TimeZone = this.zone) = MascotBrain(
        dao = mascots,
        facts = FactsBuilder(diary, goals, PersonalRepository(diary, FakePersonalDao(), FakeBodyMetricDao(), goals, zone), zone),
        diaryDao = diary,
        zone = zone,
        random = Random(1),
        asking = { asking },
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

    // --- Серия и итог недели ----------------------------------------------------------------

    /** Знакомы: имя названо, писать разрешили — вопросов на запись не будет. */
    private suspend fun known() = mascots.upsertProfile(MascotProfileEntity(name = "Женя", nameAsked = true, consent = WriteConsent.ALLOWED))

    /** Понедельник, 9 ноября: прошлая неделя — 2–8 ноября. */
    private val monday = Instant.parse("2026-11-09T09:00:00Z")

    @Test
    fun `третий день подряд с записями — Яйцо отмечает серию сообщением в ленте`() = runTest {
        known()
        eat("Гречка", now - 2.days)
        eat("Кефир", now - 1.days)

        val reaction = brain().react(MascotEvent.Ate(eat("Творог", now)), now)

        val said = mascots.messages.value.single { it.topic == Topic.STREAK_DAY.name }
        assertEquals(Mascot.EGG, said.mascot)
        assertEquals(today, said.dateEpochDay)
        assertTrue("три дня" in said.text.lowercase(), said.text)
        assertNull(reaction?.speech, "на шаг человека — одно сообщение: облачка вдобавок нет")
    }

    @Test
    fun `серию отмечают раз в день и только в круглые дни`() = runTest {
        known()
        eat("Гречка", now - 2.days)
        eat("Кефир", now - 1.days)
        val brain = brain()

        brain.react(MascotEvent.Ate(eat("Творог", now)), now)
        brain.react(MascotEvent.Ate(eat("Яблоко", now + 1.hours)), now + 1.hours)
        brain.react(MascotEvent.Ate(eat("Гречка", now + 1.days)), now + 1.days)

        assertEquals(1, mascots.messages.value.count { it.topic == Topic.STREAK_DAY.name }, "четвёртый день — не повод")
    }

    @Test
    fun `итог прошлой недели — Масло на запись новой недели, один раз`() = runTest {
        known()
        goals.upsert(DailyGoalEntity(fromDateEpochDay = 0, kcal = 220, prot = 10, fat = 5, carb = 40))
        eat("Гречка", Instant.parse("2026-11-02T13:00:00Z"))
        eat("Гречка", Instant.parse("2026-11-04T13:00:00Z"))
        eat("Творог", Instant.parse("2026-11-06T13:00:00Z"), prot100 = 1_800, carb100 = 300)
        val brain = brain()

        brain.react(MascotEvent.Ate(eat("Кефир", monday)), monday)
        brain.react(MascotEvent.Ate(eat("Яблоко", monday + 2.hours)), monday + 2.hours)

        val said = mascots.messages.value.single { it.topic == Topic.WEEK_SUMMARY.name }
        assertEquals(Mascot.BUTTER, said.mascot)
        assertTrue("три дня" in said.text.lowercase(), said.text)
    }

    @Test
    fun `за прошлую неделю меньше двух дней — итога нет`() = runTest {
        known()
        eat("Гречка", Instant.parse("2026-11-04T13:00:00Z"))

        brain().react(MascotEvent.Ate(eat("Кефир", monday)), monday)

        assertTrue(mascots.messages.value.none { it.topic == Topic.WEEK_SUMMARY.name })
    }

    @Test
    fun `итог недели и серия в один шаг не звучат — сначала итог, серия на следующий`() = runTest {
        known()
        // суббота и воскресенье: за неделю два дня, а с понедельником — третий подряд
        eat("Гречка", Instant.parse("2026-11-07T13:00:00Z"))
        eat("Кефир", Instant.parse("2026-11-08T13:00:00Z"))
        val brain = brain()

        brain.react(MascotEvent.Ate(eat("Творог", monday)), monday)
        assertEquals(listOf(Topic.WEEK_SUMMARY.name), mascots.messages.value.map { it.topic })

        brain.react(MascotEvent.Ate(eat("Яблоко", monday + 2.hours)), monday + 2.hours)
        assertEquals(listOf(Topic.WEEK_SUMMARY.name, Topic.STREAK_DAY.name), mascots.messages.value.map { it.topic })
    }

    @Test
    fun `вопрос важнее итога и серии`() = runTest {
        eat("Гречка", now - 2.days)
        eat("Кефир", now - 1.days)

        brain().react(MascotEvent.Ate(eat("Творог", now)), now)

        assertEquals(listOf(MascotQuestion.NAME), mascots.messages.value.map { it.question })
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
    fun `час назад — не встречают ночью — тем более`() = runTest {
        mascots.upsertProfile(MascotProfileEntity(lastOpenAt = (now - 1.hours).toEpochMilliseconds()))
        assertNull(brain().onAppOpened(now))

        val night = Instant.parse("2026-11-07T02:00:00Z")
        mascots.upsertProfile(MascotProfileEntity(lastOpenAt = (night - 9.hours).toEpochMilliseconds()))
        assertNull(brain().onAppOpened(night))
    }

    // --- Реакции --------------------------------------------------------------------------

    @Test
    fun `третий тык — реплика первый и второй — молча`() = runTest {
        val brain = brain()

        assertNull(brain.react(MascotEvent.Poked(Mascot.EGG, 1), now)?.speech)
        assertNull(brain.react(MascotEvent.Poked(Mascot.EGG, 2), now)?.speech)
        assertEquals(Mascot.EGG, brain.react(MascotEvent.Poked(Mascot.EGG, 3), now)?.speech?.mascot)
    }

    @Test
    fun `еда — гордится хозяин новая — с репликой`() = runTest {
        mascots.upsertProfile(MascotProfileEntity(name = "Женя", nameAsked = true))
        val curd = eat("Творог", now, prot100 = 1_700, carb100 = 300)

        val reaction = brain().react(MascotEvent.Ate(curd), now)

        assertEquals(Cue(CueKind.PROUD, Mascot.EGG), reaction?.cue)
        assertEquals(Mascot.EGG, reaction?.speech?.mascot)
    }

    @Test
    fun `открыл из шторки — отмечено прочитанным автор радуется`() = runTest {
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
    fun `разрешение писать спрашивают со следующей записью — не вслед за ответом на имя`() = runTest {
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
    fun `ответ ложится в сам вопрос и второй раз его не принимают`() = runTest {
        val brain = brain()
        brain.react(MascotEvent.Ate(eat("Гречка", now)), now)

        assertTrue(brain.answerName(null, now))
        assertEquals(Choice.NO, mascots.messages.value.single { it.question == MascotQuestion.NAME }.choice)

        assertFalse(brain.answerName("Женя", now), "на отвеченный вопрос второй раз не отвечают")
        assertNull(mascots.profileRow.value?.name)
        assertTrue(mascots.messages.value.none { it.topic == Topic.NAME_THANKS.name })
    }

    @Test
    fun `вопрос держит слово пока он после последней записи и без ответа`() = runTest {
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
    fun `новость ждёт пока человек не начнёт вести дневник`() = runTest {
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
    fun `пока вопрос ждёт ответа новость ждёт тоже`() = runTest {
        val brain = brain()
        brain.react(MascotEvent.Ate(eat("Гречка", now - 10.minutes)), now - 10.minutes)

        val said = async { brain.report(BackgroundModel.SEARCH, ModelDelivery.READY, at(now)) }
        advanceUntilIdle()
        assertFalse(said.isCompleted)

        brain.answerName(null, now)
        advanceUntilIdle()

        assertEquals(Topic.SEARCH_READY.name, said.await()?.topic)
    }

    /** Просьба браузера пустить в дневник — тоже вопрос: новость его не перебивает. */
    @Test
    fun `пока просьба ждёт ответа новость ждёт тоже`() = runTest {
        val brain = brain()
        eat("Гречка", now - 10.minutes)
        asking.value = listOf((now - 5.minutes).toEpochMilliseconds())

        val said = async { brain.offerWeb(used = { false }, clock = at(now)) }
        advanceUntilIdle()
        assertFalse(said.isCompleted)

        asking.value = emptyList()
        advanceUntilIdle()

        assertEquals(MascotQuestion.WEB, said.await()?.question)
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
    fun `о начале докачки сообщают один раз и после перезапуска тоже`() = runTest {
        started()
        brain().report(BackgroundModel.SEARCH, ModelDelivery.DOWNLOADING, at(now))

        assertNull(brain().report(BackgroundModel.SEARCH, ModelDelivery.DOWNLOADING, at(now + 2.days)))
    }

    @Test
    fun `о том что докачалось сообщают один раз`() = runTest {
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

    // --- Приложение новое ---------------------------------------------------------------

    /** Первый день занят знакомством, именем и вопросом про уведомления — ещё одно сообщение там лишнее. */
    @Test
    fun `в первый день о новизне приложения молчат`() = runTest {
        eat("Гречка", now - 2.hours)

        assertNull(brain().tellAppIsNew(at(now)))
        assertTrue(mascots.messages.value.isEmpty())
    }

    @Test
    fun `со второго дня Масло зовёт писать разработчику — в ленту и с почтой`() = runTest {
        started()

        val said = brain().tellAppIsNew(at(now))

        assertNotNull(said)
        assertEquals(Topic.APP_NEW.name, said.topic)
        assertEquals(Mascot.BUTTER, said.mascot)
        assertEquals(Delivery.FEED, said.delivery)
        assertTrue(FEEDBACK_EMAIL in said.text, said.text)
    }

    @Test
    fun `о новизне приложения говорят один раз за всю жизнь`() = runTest {
        started()
        brain().tellAppIsNew(at(now))

        assertNull(brain().tellAppIsNew(at(now + 3.days)))
    }

    /** По немногу каждый день: сегодня персонажи уже говорили — скажут в другой день. */
    @Test
    fun `если сегодня уже говорили — ждут другого дня`() = runTest {
        started()
        mascots.insert(message(Mascot.EGG).copy(delivery = Delivery.FEED))

        assertNull(brain().tellAppIsNew(at(now)))
        assertNotNull(brain().tellAppIsNew(at(now + 1.days)))
    }

    // --- Страница дневника в браузере ----------------------------------------------------

    /** Сколько держать страницу, когда телефон убран, — выбирают на странице, хранится в профиле. */
    @Test
    fun `запас страницы хранится в профиле`() = runTest {
        assertEquals(PageGrace.MIN_5, brain().profile.first().pageGrace, "по умолчанию — пять минут")

        brain().setPageGrace(PageGrace.FOREVER)

        assertEquals(PageGrace.FOREVER, mascots.profileRow.value?.pageGrace)
    }

    /** Цель шагов и рост — из шита шагов, хранятся в профиле; пока их не задали — 8 000 и 175 см. */
    @Test
    fun `цель шагов и рост хранятся в профиле`() = runTest {
        val fresh = brain().profile.first()
        assertEquals(DEFAULT_STEPS_GOAL, fresh.stepsGoalOrDefault)
        assertEquals(DEFAULT_HEIGHT_CM, fresh.heightOrDefault)

        brain().setSteps(goal = 10_000, heightCm = 182)

        assertEquals(10_000, mascots.profileRow.value?.stepsGoal)
        assertEquals(182, mascots.profileRow.value?.heightCm)
    }

    /** Рост, названный помощнику цели, второй раз не спрашивают: по нему же меряется шаг. */
    @Test
    fun `рост из помощника цели запоминается, цель шагов не трогается`() = runTest {
        brain().setSteps(goal = 6_000, heightCm = 170)

        brain().setHeight(158)

        assertEquals(158, mascots.profileRow.value?.heightCm)
        assertEquals(6_000, mascots.profileRow.value?.stepsGoal)
    }

    /** Имя меняют и со страницы: оно же больше не спрашивается — человек его уже назвал. */
    @Test
    fun `имя меняется в профиле и больше не спрашивается`() = runTest {
        brain().setName("Женя")

        assertEquals("Женя", mascots.profileRow.value?.name)
        assertEquals(true, mascots.profileRow.value?.nameAsked)
    }

    /** Итог прогулки по дорожке — одной репликой Зёрнышка в ленте: сколько прошли и сколько до цели. */
    @Test
    fun `после прогулки Зёрнышко пишет в ленту, сколько прошли`() = runTest {
        started()
        val walk = Walk(startedAt = 0, seenAt = 0, distanceM = 2140, kcal = 90, elapsedS = 32 * 60, steps = 3242.4, topSpeedKmh = 4.5)

        val said = brain().tellWalk(walk, WalkTotals(steps = 5242, distanceM = 3500, elapsedS = 3000, kcal = 150, walks = 2), goal = 8000, clock = at(now))

        assertNotNull(said)
        assertEquals(Topic.WALK_DONE.name, said.topic)
        assertEquals(Mascot.GRAIN, said.mascot)
        assertEquals(Delivery.FEED, said.delivery)
        assertTrue("3 242 шага" in said.text && "2,1 км" in said.text && "2 758 шагов" in said.text, said.text)
    }

    @Test
    fun `прогулка добрала цель дня — Зёрнышко говорит о цели`() = runTest {
        started()
        val walk = Walk(startedAt = 0, seenAt = 0, distanceM = 2140, kcal = 90, elapsedS = 32 * 60, steps = 3242.0, topSpeedKmh = 4.5)

        val said = brain().tellWalk(walk, WalkTotals(steps = 8100, distanceM = 6000, elapsedS = 5000, kcal = 250, walks = 3), goal = 8000, clock = at(now))

        assertEquals(Topic.WALK_GOAL.name, said?.topic)
        assertTrue("8 100 шагов" in said!!.text, said.text)
    }

    /** Функцию предлагают, а не навязывают: сначала — что она даёт и «рассказать?», без адреса. */
    @Test
    fun `дневник с компьютера Зёрнышко предлагает вопросом, без инструкции`() = runTest {
        started()

        val said = brain().offerWeb(used = { false }, clock = at(now))

        assertNotNull(said)
        assertEquals(MascotQuestion.WEB, said.question)
        assertEquals(Mascot.GRAIN, said.mascot)
        assertFalse(PAGE in said.text, said.text)
    }

    /** «Да» — и только тогда инструкция с адресом. */
    @Test
    fun `на «да» — инструкция с адресом страницы`() = runTest {
        started()
        val brain = brain()
        brain.offerWeb(used = { false }, clock = at(now))

        assertTrue(brain.answerWeb(yes = true, address = PAGE, now = now + 1.minutes))

        val reply = mascots.messages.value.last()
        assertEquals(Topic.WEB_ADDRESS.name, reply.topic)
        assertTrue(PAGE in reply.text, reply.text)
        assertFalse(brain.answerWeb(yes = true, address = PAGE, now = now + 2.minutes), "второй тап ничего не запускает")
    }

    @Test
    fun `на «нет» — без адреса, и как вернуться к этому`() = runTest {
        started()
        val brain = brain()
        brain.offerWeb(used = { false }, clock = at(now))

        brain.answerWeb(yes = false, address = PAGE, now = now + 1.minutes)

        val reply = mascots.messages.value.last()
        assertEquals(Topic.WEB_LATER.name, reply.topic)
        assertFalse(PAGE in reply.text)
    }

    /** Не пользуются — напоминают раз в месяц, не чаще; пользуются — молчат. */
    @Test
    fun `предложение повторяется через месяц, пока страницей не пользуются`() = runTest {
        started()
        val brain = brain()
        brain.offerWeb(used = { false }, clock = at(now))

        assertNull(brain.offerWeb(used = { false }, clock = at(now + 20.days)), "через три недели — рано")
        eat("Гречка", now + 31.days - 1.hours)
        assertNotNull(brain.offerWeb(used = { false }, clock = at(now + 31.days)), "через месяц — снова")
        assertNull(brain.offerWeb(used = { true }, clock = at(now + 70.days)), "уже пользуются — молчат")
    }

    /** Человек спросил — отвечают сразу и сколько угодно раз: он хочет освежить в памяти. */
    @Test
    fun `по просьбе адрес называют снова`() = runTest {
        started()
        brain().answerWebAddress(PAGE, now)

        val again = brain().answerWebAddress(PAGE, now + 1.hours)

        assertEquals(Topic.WEB_ADDRESS.name, again?.topic)
        assertTrue(PAGE in again!!.text)
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
    fun `«Пишите» ждёт ответа телефона а Зёрнышко отвечает по итогу`() = runTest {
        asked(MascotQuestion.WRITE)
        val brain = brain()

        assertTrue(brain.answerWrite(allow = true, now))
        assertEquals(WriteConsent.ALLOWED, mascots.profileRow.value?.consent)
        assertEquals(Choice.YES, mascots.messages.value.single().choice, "ответит телефон — тогда и Зёрнышко")
        assertEquals(Topic.WRITE_YES.name, brain.notificationsResult(granted = true, now)?.topic)
        assertEquals(Topic.WRITE_DENIED.name, brain.notificationsResult(granted = false, now)?.topic)
    }

    @Test
    fun `«Не надо» — согласие отозвано Зёрнышко обещает молчать`() = runTest {
        asked(MascotQuestion.WRITE)

        assertTrue(brain().answerWrite(allow = false, now))

        assertEquals(WriteConsent.DECLINED, mascots.profileRow.value?.consent)
        val reply = mascots.messages.value.last()
        assertEquals(Topic.WRITE_NO.name, reply.topic)
        assertEquals(Mascot.GRAIN, reply.mascot)
    }

    /** Корзинка убирает сообщение из переписки, но персонажи помнят, что уже говорили. */
    @Test
    fun `скрытое сообщение уходит из переписки, но не из памяти`() = runTest {
        started()
        val brain = brain()
        val said = brain.answerWebAddress(PAGE, now)!!

        brain.hide(said.id, now)

        assertTrue(brain.messages(today).first().none { it.id == said.id })
        assertTrue(said.id in mascots.recent(10).map { it.id }, "персонажи помнят сказанное")
    }

    @Test
    fun `вопрос без ответа корзинкой не скрывается`() = runTest {
        val id = asked(MascotQuestion.TREADMILL)

        brain().hide(id, now)

        assertEquals(1, brain().messages(today).first().size)
    }

    /** Про шаги телефона Зёрнышко спрашивает само один раз; ответ ложится в вопрос и второй раз не принимается. */
    @Test
    fun `про шаги телефона спрашивают один раз, ответ — тоже один`() = runTest {
        started()
        val brain = brain()

        assertEquals(MascotQuestion.STEPS.topic, brain.askSteps(at(now))?.topic)
        assertNull(brain.askSteps(at(now + 3.days)))
        assertTrue(brain.answerSteps(yes = true, now))
        assertFalse(brain.answerSteps(yes = false, now + 1.minutes))
    }

    @Test
    fun `на «Не надо» про шаги Зёрнышко отвечает сразу, на «Считать» — после ответа телефона`() = runTest {
        asked(MascotQuestion.STEPS)
        val brain = brain()
        brain.answerSteps(yes = false, now)
        assertEquals(Topic.STEPS_NO.name, mascots.messages.value.last().topic)

        assertEquals(Topic.STEPS_YES.name, brain.stepsResult(granted = true, now)?.topic)
        assertEquals(Topic.STEPS_FAILED.name, brain.stepsResult(granted = false, now)?.topic)
    }

    /** Про дорожку Зёрнышко спрашивает само один раз — вопросом в ленте, в свою очередь. */
    @Test
    fun `про беговую дорожку спрашивают один раз`() = runTest {
        started()

        val asked = brain().askTreadmill(at(now))

        assertEquals(MascotQuestion.TREADMILL.topic, asked?.topic)
        assertEquals(Mascot.GRAIN, asked?.mascot)
        assertNull(brain().askTreadmill(at(now + 3.days)))
    }

    /** Браузер пустили — и про дорожку, и про шаги пора спросить в одну секунду: говорят всё равно по одному. */
    @Test
    fun `два вопроса разом не задаются — второй ждёт ответа на первый`() = runTest {
        started()
        val brain = brain()

        val treadmill = async { brain.askTreadmill(at(now)) }
        val steps = async { brain.askSteps(at(now)) }
        advanceUntilIdle()

        assertEquals(listOf<MascotQuestion?>(MascotQuestion.TREADMILL), mascots.messages.value.map { it.question })
        assertFalse(steps.isCompleted)

        brain.answerTreadmill(false, now + 1.minutes)
        advanceUntilIdle()

        assertEquals(MascotQuestion.STEPS, steps.await()?.question)
        assertEquals(MascotQuestion.TREADMILL, treadmill.await()?.question)
    }

    /** Повторный тап по «Подключить» не открывает выбор дорожки второй раз. */
    @Test
    fun `на отвеченный вопрос про дорожку второй ответ не принимается`() = runTest {
        asked(MascotQuestion.TREADMILL)
        val brain = brain()

        assertTrue(brain.answerTreadmill(yes = true, now))
        assertFalse(brain.answerTreadmill(yes = false, now + 1.minutes))
        assertEquals(Choice.YES, mascots.messages.value.single().choice)
    }

    @Test
    fun `на «Не надо» про дорожку Зёрнышко отвечает сразу, на «Подключить» — после выбора дорожки`() = runTest {
        asked(MascotQuestion.TREADMILL)
        val brain = brain()
        brain.answerTreadmill(yes = false, now)
        assertEquals(Topic.TREADMILL_NO.name, mascots.messages.value.last().topic)

        assertEquals(Topic.TREADMILL_YES.name, brain.treadmillResult(connected = true, now)?.topic)
        assertEquals(Topic.TREADMILL_FAILED.name, brain.treadmillResult(connected = false, now)?.topic)
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
    private val PAGE = "http://192.168.1.5:47800/"

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
