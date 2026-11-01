package n7.kcalai.mascot

import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import n7.kcalai.database.Delivery
import n7.kcalai.database.DiaryDao
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.MascotDao
import n7.kcalai.database.MascotMessageEntity
import n7.kcalai.database.MascotProfileEntity
import n7.kcalai.database.WriteConsent
import n7.kcalai.database.nutriments
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.mascot.voice.Utterance
import n7.kcalai.mascot.voice.Voices
import n7.kcalai.mascot.voice.compose
import n7.kcalai.model.Mascot
import n7.kcalai.model.Sex
import n7.kcalai.model.owner
import n7.kcalai.model.parseFoodRef
import n7.kcalai.model.serialize

/**
 * Сообщение для шторки.
 *
 * @param suggestion что предложить записать одним нажатием; `null` — кнопки нет
 * @param thread непрочитанные сегодня сообщения этого персонажа, старые первыми, — переписка в шторке
 */
data class Written(
    val message: MascotMessageEntity,
    val suggestion: Suggestion?,
    val thread: List<MascotMessageEntity>,
)

/**
 * Мозг персонажей: когда написать, что ответить, как отозваться на касание и еду.
 *
 * Всё считается на телефоне, по дневнику человека: ни один факт не уходит в сеть.
 * Сообщения первыми и ответы в ленте помнятся в базе, облачки — только в памяти:
 * они живут секунды, и хранить их незачем.
 */
class MascotBrain(
    private val dao: MascotDao,
    private val facts: FactsBuilder,
    private val diaryDao: DiaryDao,
    private val zone: TimeZone,
    private val random: Random = Random.Default,
) {

    /** Профиль; пока его строки нет — профиль по умолчанию: ничего не спрашивали, ничего не знаем. */
    val profile: Flow<MascotProfileEntity> = dao.observeProfile().map { it ?: MascotProfileEntity() }

    fun messages(dateEpochDay: Long): Flow<List<MascotMessageEntity>> = dao.observeDay(dateEpochDay)

    /** Сказанное в облачках, новые первыми. Память на сессию: облачко живёт секунды. */
    private val speechMemory = ArrayDeque<List<String>>()
    private var lastShaken: Instant? = null

    // --- Пишет первым ---------------------------------------------------------------------

    /**
     * Написать первым, если есть повод и можно.
     *
     * @param notificationsEnabled разрешены ли уведомления в системе. Если нет —
     *        сообщение не пишется вовсе: ненаписанное не тратит дневной бюджет
     *        и не считается проигнорированным.
     */
    suspend fun write(now: Instant, notificationsEnabled: Boolean): Written? {
        if (!notificationsEnabled) return null
        val profile = dao.profile() ?: MascotProfileEntity()
        if (profile.consent != WriteConsent.ALLOWED) return null

        val snapshot = facts.build(now, profile)
        val recent = dao.recent(MEMORY)
        val sent = recent.filter { it.delivery == Delivery.NOTIFICATION }.mapNotNull { it.sent() }
        val lastOpenAt = profile.lastOpenAt?.let(Instant::fromEpochMilliseconds)
        val nudge = NudgePlanner.plan(snapshot, sent, lastOpenAt, now, zone) ?: return null
        return note(nudge.topic, nudge.mascot, snapshot, recent, now)
    }

    /** Сообщение на заданную тему — мимо планировщика. Для проверки на устройстве. */
    suspend fun write(topic: Topic, mascot: Mascot, now: Instant): Written? =
        note(topic, mascot, facts.build(now, dao.profile()), dao.recent(MEMORY), now)

    private suspend fun note(
        topic: Topic,
        mascot: Mascot,
        snapshot: Snapshot,
        recent: List<MascotMessageEntity>,
        now: Instant,
    ): Written? {
        val said = Voices.of(mascot).compose(topic, snapshot.facts, recent.memory(), random) ?: return null
        val message = save(mascot, topic, said, Delivery.NOTIFICATION, snapshot.facts.now.date.toEpochDays(), now)
        val thread = (recent + message)
            .filter { it.mascot == mascot && it.dateEpochDay == message.dateEpochDay && it.openedAt == null }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
        return Written(message, snapshot.suggestions[topic], thread)
    }

    // --- Отвечает в ленте -----------------------------------------------------------------

    /** Ответ на действие человека — в ленту дня. [food] — о какой еде речь. */
    suspend fun answer(topic: Topic, mascot: Mascot, now: Instant, food: String? = null): MascotMessageEntity? {
        val facts = lightFacts(now).copy(food = food)
        val said = Voices.of(mascot).compose(topic, facts, dao.recent(MEMORY).memory(), random) ?: return null
        return save(mascot, topic, said, Delivery.FEED, facts.now.date.toEpochDays(), now)
    }

    /**
     * Имя названо — или «Не скажу». Второй раз не спрашивают ни в том, ни в другом случае.
     *
     * @return ответ Яйца по имени; `null`, если имя не назвали — благодарить не за что
     */
    suspend fun answerName(name: String?, now: Instant): MascotMessageEntity? {
        val profile = dao.profile() ?: MascotProfileEntity()
        dao.upsertProfile(profile.copy(name = name ?: profile.name, nameAsked = true))
        return name?.let { answer(Topic.NAME_THANKS, Mascot.EGG, now) }
    }

    /** Пол из помощника цели: с ним персонажи говорят «ты ел» и «ты ела» правильно. */
    suspend fun setSex(sex: Sex) {
        val profile = dao.profile() ?: MascotProfileEntity()
        if (profile.sex != sex) dao.upsertProfile(profile.copy(sex = sex))
    }

    /**
     * «Пишите» или «Не надо».
     *
     * @return ответ на «Не надо»; на «Пишите» отвечают после ответа телефона — [notificationsResult]
     */
    suspend fun answerWrite(allow: Boolean, now: Instant): MascotMessageEntity? {
        val profile = dao.profile() ?: MascotProfileEntity()
        val consent = if (allow) WriteConsent.ALLOWED else WriteConsent.DECLINED
        dao.upsertProfile(profile.copy(consent = consent, consentAt = now.toEpochMilliseconds()))
        return if (allow) null else answer(Topic.WRITE_NO, Mascot.GRAIN, now)
    }

    /** Телефон разрешил уведомления или нет. */
    suspend fun notificationsResult(granted: Boolean, now: Instant): MascotMessageEntity? =
        answer(if (granted) Topic.WRITE_YES else Topic.WRITE_DENIED, Mascot.GRAIN, now)

    // --- Отзывается -----------------------------------------------------------------------

    /**
     * Человек открыл приложение: всё написанное прочитано, время визита запомнено.
     *
     * @return встреча, если его не было три часа и больше; ночью не встречают — спят
     */
    suspend fun onAppOpened(now: Instant): Reaction? {
        val profile = dao.profile() ?: MascotProfileEntity()
        val nowMillis = now.toEpochMilliseconds()
        dao.markAllOpened(nowMillis)
        dao.upsertProfile(profile.copy(lastOpenAt = nowMillis))

        val away = profile.lastOpenAt?.let { ((nowMillis - it) / MINUTE_MS).toInt() } ?: return null
        if (away < GREET_AFTER_MINUTES || isNight(now.toLocalDateTime(zone).hour)) return null
        val topic = if (away >= DAY_MINUTES) Topic.GREET_DAYS else Topic.GREET_HOURS
        val speech = speak(Mascot.entries.random(random), topic, now) { it.copy(absentMinutes = away) }
        return Reaction(Cue(CueKind.GREET, null), speech)
    }

    suspend fun react(event: MascotEvent, now: Instant): Reaction? = when (event) {
        is MascotEvent.Poked -> when (event.count) {
            POKES_TO_TALK -> speak(event.mascot, Topic.POKE, now)
            POKES_TO_SULK -> speak(event.mascot, Topic.POKE_ANNOYED, now)
            else -> null
        }.said()
        is MascotEvent.Trick -> maybe(TRICK_CHANCE) { speak(event.mascot, Topic.TRICK, now) }.said()
        is MascotEvent.Held -> maybe(HELD_CHANCE) { speak(event.mascot, Topic.HELD, now) }.said()
        is MascotEvent.Dropped -> maybe(DROPPED_CHANCE) {
            speak(event.mascot, if (event.thrown) Topic.THROWN else Topic.DROPPED, now)
        }.said()
        is MascotEvent.Petted -> speak(event.mascot, Topic.PETTED, now).said()
        is MascotEvent.Woken ->
            if (event.night) {
                speak(event.mascot, Topic.WOKEN_NIGHT, now).said()
            } else {
                maybe(WOKEN_CHANCE) { speak(event.mascot, Topic.WOKEN_IDLE, now) }.said()
            }
        is MascotEvent.Ate -> ate(event.entry, now)
        MascotEvent.GoalReached -> {
            val mascot = if (random.nextBoolean()) Mascot.EGG else Mascot.BUTTER
            Reaction(Cue(CueKind.CELEBRATE, null), speak(mascot, Topic.GOAL_REACHED, now))
        }
        is MascotEvent.Opened -> opened(event.messageId, now)
        MascotEvent.Shaken -> shaken(now)
    }

    /**
     * Еда записана: гордится хозяин макроса. Говорит — когда есть что сказать:
     * новинка, третий день подряд, ночь, первая запись дня; иначе — изредка.
     */
    private suspend fun ate(entry: DiaryEntryEntity, now: Instant): Reaction {
        val owner = entry.nutriments().owner()
        val day = entry.dateEpochDay
        val history = diaryDao.since(day - FOOD_LOOKBACK_DAYS).filter { it.id != entry.id }
        val key = entry.foodKey()
        val same = history.filter { it.foodKey() == key }
        val sameDays = same.mapTo(HashSet()) { it.dateEpochDay } + day
        val run = generateSequence(day) { it - 1 }.takeWhile { it in sameDays }.count()
        val hour = Instant.fromEpochMilliseconds(entry.createdAt).toLocalDateTime(zone).hour

        val topic = when {
            same.isEmpty() -> Topic.MEAL_NEW
            run >= REPEAT_RUN -> Topic.MEAL_REPEAT
            hour >= 23 || hour < 5 -> Topic.MEAL_LATE
            history.none { it.dateEpochDay == day } -> Topic.MEAL_FIRST
            random.nextDouble() < OWNER_CHANCE -> Topic.MEAL_OWNER
            else -> null
        }
        val speech = topic?.let { speak(owner, it, now) { facts -> facts.copy(food = entry.displayName, foodDaysInRow = run) } }
        return Reaction(Cue(CueKind.PROUD, owner), speech)
    }

    private suspend fun opened(messageId: Long, now: Instant): Reaction? {
        val message = dao.findById(messageId) ?: return null
        dao.markOpened(message.id, now.toEpochMilliseconds())
        return Reaction(Cue(CueKind.OPENED, message.mascot), speak(message.mascot, Topic.OPENED, now))
    }

    /** Трясут — кружится голова у всех. Не чаще раза в десять секунд: тряска длится, а реплика — одна. */
    private suspend fun shaken(now: Instant): Reaction? {
        if (lastShaken?.let { now - it < SHAKE_PAUSE } == true) return null
        lastShaken = now
        return Reaction(Cue(CueKind.DIZZY, null), speak(Mascot.entries.random(random), Topic.SHAKEN, now))
    }

    private suspend fun speak(mascot: Mascot, topic: Topic, now: Instant, tune: (Facts) -> Facts = { it }): Speech? {
        val facts = tune(lightFacts(now))
        val said = Voices.of(mascot).compose(topic, facts, speechMemory.toList(), random) ?: return null
        speechMemory.addFirst(said.lineIds)
        if (speechMemory.size > SPEECH_MEMORY) speechMemory.removeLast()
        return Speech(mascot, said.text)
    }

    private inline fun maybe(chance: Double, block: () -> Speech?): Speech? =
        if (random.nextDouble() < chance) block() else null

    private fun Speech?.said(): Reaction? = this?.let { Reaction(null, it) }

    /** Факты для короткой реплики: кто человек и который час. Дневник для облачка не нужен. */
    private suspend fun lightFacts(now: Instant): Facts {
        val profile = dao.profile()
        return Facts(now = now.toLocalDateTime(zone), name = profile?.name, sex = profile?.sex)
    }

    private suspend fun save(
        mascot: Mascot,
        topic: Topic,
        said: Utterance,
        delivery: Delivery,
        day: Long,
        now: Instant,
    ): MascotMessageEntity {
        val message = MascotMessageEntity(
            mascot = mascot,
            topic = topic.name,
            text = said.text,
            lineIds = said.lineIds.joinToString(","),
            delivery = delivery,
            dateEpochDay = day,
            createdAt = now.toEpochMilliseconds(),
        )
        return message.copy(id = dao.insert(message))
    }

    private companion object {
        /** Сколько последних сообщений помнится: варианты не повторяются, бюджет считается. */
        const val MEMORY = 40
        const val SPEECH_MEMORY = 30
        const val MINUTE_MS = 60_000L
        const val DAY_MINUTES = 24 * 60
        const val GREET_AFTER_MINUTES = 180
        const val POKES_TO_TALK = 3
        const val POKES_TO_SULK = 6
        const val TRICK_CHANCE = 0.5
        const val HELD_CHANCE = 0.7
        const val DROPPED_CHANCE = 0.6
        const val WOKEN_CHANCE = 0.4
        const val OWNER_CHANCE = 0.35
        const val REPEAT_RUN = 3
        const val FOOD_LOOKBACK_DAYS = 89
        val SHAKE_PAUSE = 10.seconds
    }
}

/** Ночь — персонажи спят: на экране их застают спящими, а встречать некому. */
fun isNight(hour: Int): Boolean = hour >= 23 || hour < 6

/**
 * Спросить ли разрешение писать первыми.
 *
 * Не спрашивали — спросить. «Не надо» — переспросить через месяц: человек мог
 * передумать, а другого способа включить сообщения у него нет.
 */
fun MascotProfileEntity.asksToWrite(now: Instant): Boolean = when (consent) {
    WriteConsent.UNASKED -> true
    WriteConsent.ALLOWED -> false
    WriteConsent.DECLINED -> consentAt?.let { now - Instant.fromEpochMilliseconds(it) >= REASK_AFTER } ?: true
}

private val REASK_AFTER = 30.days

private fun MascotMessageEntity.sent(): Sent? =
    Topic.entries.firstOrNull { it.name == topic }?.let { Sent(it, mascot, Instant.fromEpochMilliseconds(createdAt), openedAt != null) }

/** Варианты сообщений, новые первыми, — для памяти «не повторяться». */
private fun List<MascotMessageEntity>.memory(): List<List<String>> =
    map { message -> message.lineIds.split(',').filter(String::isNotEmpty) }

/** Еда по ссылке на продукт, а без неё — по названию. */
private fun DiaryEntryEntity.foodKey(): String =
    parseFoodRef(foodRef)?.serialize() ?: displayName.trim().lowercase()
