package n7.kcalai.mascot

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import n7.kcalai.database.Delivery
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.MascotMessageEntity
import n7.kcalai.database.MascotProfileEntity
import n7.kcalai.database.WriteConsent
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.EntrySource
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.personal.PersonalRepository

/**
 * Мозг персонажей: пишет первым, отвечает на ответы и реагирует на касания и еду.
 */
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

    // --- Имя и согласие -------------------------------------------------------------------

    @Test
    fun `ответ на имя — по имени и в ленту`() = runTest {
        val thanks = brain().answerName("Женя", now)

        assertEquals("Женя", mascots.profileRow.value?.name)
        assertEquals(true, mascots.profileRow.value?.nameAsked)
        assertNotNull(thanks)
        assertTrue("Женя" in thanks.text, thanks.text)
        assertEquals(Mascot.EGG, thanks.mascot)
        assertEquals(Delivery.FEED, thanks.delivery)
    }

    @Test
    fun `«Не скажу» — второй раз не спрашивают`() = runTest {
        assertNull(brain().answerName(null, now))

        assertEquals(true, mascots.profileRow.value?.nameAsked)
        assertNull(mascots.profileRow.value?.name)
    }

    @Test
    fun `«Пишите» ждёт ответа телефона, а Зёрнышко отвечает по итогу`() = runTest {
        val brain = brain()

        assertNull(brain.answerWrite(allow = true, now))
        assertEquals(WriteConsent.ALLOWED, mascots.profileRow.value?.consent)
        assertEquals(Topic.WRITE_YES.name, brain.notificationsResult(granted = true, now)?.topic)
        assertEquals(Topic.WRITE_DENIED.name, brain.notificationsResult(granted = false, now)?.topic)
    }

    @Test
    fun `«Не надо» — согласие отозвано, Зёрнышко обещает молчать`() = runTest {
        val reply = brain().answerWrite(allow = false, now)

        assertEquals(WriteConsent.DECLINED, mascots.profileRow.value?.consent)
        assertEquals(Topic.WRITE_NO.name, reply?.topic)
        assertEquals(Mascot.GRAIN, reply?.mascot)
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
