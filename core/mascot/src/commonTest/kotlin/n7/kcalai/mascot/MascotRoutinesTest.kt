package n7.kcalai.mascot

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import n7.kcalai.database.ContributionDao
import n7.kcalai.database.ContributionEntity
import n7.kcalai.database.Delivery
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.MascotMessageEntity
import n7.kcalai.database.MascotProfileEntity
import n7.kcalai.database.WriteConsent
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.BackgroundModel
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.ModelDelivery
import n7.kcalai.model.Nutriments
import n7.kcalai.model.TreadmillSample
import n7.kcalai.repositories.WalkRepository
import n7.kcalai.personal.PersonalRepository
import n7.kcalai.repositories.DiaryRepository

/**
 * Что персонажи делают вне экрана. Это общий код: Android и iOS дают только шторку
 * и будильник, а поведение у них одно — то, что проверено здесь.
 */
class MascotRoutinesTest {

    private val zone = TimeZone.UTC
    private val diary = FakeDiaryDao()
    private val goals = FakeGoalDao()
    private val mascots = FakeMascotDao()
    private val shade = FakeShade()
    private val walkDao = FakeWalkDao()
    private val walks = WalkRepository(walkDao, zone)

    /** Пятница, 16:00. */
    private val now = Instant.parse("2026-11-06T16:00:00Z")

    private val clock = object : Clock {
        override fun now(): Instant = this@MascotRoutinesTest.now
    }

    private fun routines(): MascotRoutines {
        val personal = PersonalRepository(diary, FakePersonalDao(), FakeBodyMetricDao(), goals, zone)
        val brain = MascotBrain(
            dao = mascots,
            facts = FactsBuilder(diary, goals, personal, zone),
            diaryDao = diary,
            zone = zone,
            random = Random(1),
        )
        return MascotRoutines(brain, DiaryRepository(diary, goals, NoContributions), personal, shade, walks, zone, clock)
    }

    private suspend fun eat(at: Instant) = diary.insert(
        DiaryEntryEntity(
            dateEpochDay = at.toEpochMilliseconds() / DAY_MS,
            meal = MealType.LUNCH,
            displayName = "Гречка",
            grams = 200,
            kcal100 = 110,
            prot100 = 420,
            fat100 = 110,
            carb100 = 2_130,
            source = EntrySource.TEXT,
            foodRef = "generic:1",
            createdAt = at.toEpochMilliseconds(),
        )
    )

    // --- Фоновый тик ------------------------------------------------------------------------

    @Test
    fun `тик — повод есть и шторка открыта — сообщение в шторке`() = runTest {
        mascots.upsertProfile(MascotProfileEntity(consent = WriteConsent.ALLOWED))
        eat(now - 4.hours)

        routines().tick()

        val posted = shade.posted.single()
        assertEquals(Topic.LONG_GAP.name, posted.message.topic)
        assertEquals(Delivery.NOTIFICATION, posted.message.delivery)
    }

    /** Ненаписанное не тратит дневной бюджет: в шторке нельзя — мозг не пишет вовсе. */
    @Test
    fun `тик — шторка закрыта — ничего не написано`() = runTest {
        mascots.upsertProfile(MascotProfileEntity(consent = WriteConsent.ALLOWED))
        eat(now - 4.hours)
        shade.open = false

        routines().tick()

        assertTrue(shade.posted.isEmpty())
        assertTrue(mascots.messages.value.isEmpty())
    }

    // --- Старт ------------------------------------------------------------------------------

    @Test
    fun `на старте Масло говорит что приложение новое`() = runTest {
        eat(now - 1.days)

        routines().onLaunch()

        assertEquals(Topic.APP_NEW.name, mascots.messages.value.single().topic)
    }

    @Test
    fun `о докачке рассказывают из потока статусов`() = runTest {
        eat(now - 1.days)

        routines().narrate(BackgroundModel.SEARCH, flowOf(ModelDelivery.READY))

        assertEquals(Topic.SEARCH_READY.name, mascots.messages.value.single().topic)
    }

    /** Прогулка кончилась — дорожка давно молчит: итог одной репликой Зёрнышка, и только раз. */
    @Test
    fun `о кончившейся прогулке Зёрнышко рассказывает один раз`() = runTest {
        eat(now - 1.days)
        walks.record(TreadmillSample(speedKmh = 4.0, distanceM = 2_140, kcal = 90, elapsedS = 1_920), (now - 10.minutes).toEpochMilliseconds())

        backgroundScope.launch { routines().tellWalks() }
        runCurrent()

        val said = mascots.messages.value.single()
        assertEquals(Topic.WALK_DONE.name, said.topic)
        assertEquals(Mascot.GRAIN, said.mascot)
        assertNotNull(walkDao.last()?.toldAt)
    }

    @Test
    fun `пока прогулка идёт, о ней молчат`() = runTest {
        eat(now - 1.days)
        walks.record(TreadmillSample(speedKmh = 4.0, distanceM = 2_140, kcal = 90, elapsedS = 1_920), (now - 10.seconds).toEpochMilliseconds())

        backgroundScope.launch { routines().tellWalks() }
        runCurrent()

        assertTrue(mascots.messages.value.isEmpty())
    }

    // --- «Записать» из шторки ---------------------------------------------------------------

    @Test
    fun `записать из шторки — еда в дневнике и ответ в той же переписке`() = runTest {
        val messageId = mascots.insert(note())
        val suggestion = Suggestion(gretchka, MealType.DINNER)

        assertTrue(routines().logFromShade(SuggestionExtras.encode(suggestion, messageId, Mascot.GRAIN)))

        val entry = diary.entries.value.single()
        assertEquals("Гречка отварная", entry.displayName)
        assertEquals(200, entry.grams)
        assertEquals(MealType.DINNER, entry.meal)
        assertEquals(EntrySource.MANUAL, entry.source)

        val reply = shade.replies.single()
        assertEquals(Mascot.GRAIN, reply.mascot)
        assertEquals(messageId, reply.original?.id)
        assertEquals("Гречка отварная, 200 г", reply.logged)
        assertEquals(Topic.LOGGED.name, reply.reply?.topic)
        assertNotNull(mascots.messages.value.single { it.id == messageId }.openedAt, "сообщение прочитано")
    }

    @Test
    fun `чужое нажатие из шторки ничего не пишет`() = runTest {
        assertFalse(routines().logFromShade(mapOf("message" to "7")))

        assertTrue(diary.entries.value.isEmpty())
        assertTrue(shade.replies.isEmpty())
    }

    private val gretchka = FoodCandidate(
        ref = FoodRef.Generic(1),
        displayName = "Гречка отварная",
        nutriments = Nutriments(kcal100 = 110, prot100 = 420, fat100 = 110, carb100 = 2_130),
        servingG = 200,
    )

    private fun note() = MascotMessageEntity(
        mascot = Mascot.GRAIN,
        topic = Topic.MISSED_MEAL.name,
        text = "Ужин сегодня был?",
        lineIds = "",
        delivery = Delivery.NOTIFICATION,
        dateEpochDay = now.toEpochMilliseconds() / DAY_MS,
        createdAt = (now - 1.hours).toEpochMilliseconds(),
    )

    /** Вклады здесь не при чём: «Записать» из шторки кладёт еду, а не продукт в общую базу. */
    private object NoContributions : ContributionDao {
        override suspend fun insert(contribution: ContributionEntity): Long = 0
        override suspend fun pending(limit: Int): List<ContributionEntity> = emptyList()
        override suspend fun pendingCount(): Int = 0
        override suspend fun markSent(ids: List<Long>, sentAt: Long) = Unit
    }

    private class FakeShade : MascotShade {
        var open = true
        val posted = mutableListOf<Written>()
        val replies = mutableListOf<ShadeReply>()

        override suspend fun canPost(): Boolean = open
        override suspend fun post(written: Written) {
            posted += written
        }
        override suspend fun postReply(reply: ShadeReply) {
            replies += reply
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
