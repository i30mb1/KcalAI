package n7.kcalai.mascot

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import n7.kcalai.database.Choice
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
import n7.kcalai.model.BackgroundModel
import n7.kcalai.model.Mascot
import n7.kcalai.model.ModelDelivery
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
    /**
     * Когда пришли сегодняшние просьбы, ждущие ответа, — от соседа и от браузера.
     * Они такие же вопросы, как вопросы персонажей: пока ждут, новость молчит.
     * Функцией, а не таблицами: что считать просьбой, мозгу знать незачем.
     */
    private val asking: (dateEpochDay: Long) -> Flow<List<Long>> = { flowOf(emptyList()) },
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

    /** Сообщение по id — первая реплика переписки в шторке после «Записать». */
    suspend fun message(id: Long): MascotMessageEntity? = dao.findById(id)

    /** Человек ответил на сообщение из шторки — значит, прочитал его. */
    suspend fun markOpened(id: Long, now: Instant) = dao.markOpened(id, now.toEpochMilliseconds())

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
     * Ответ ложится в сам вопрос: он остаётся в переписке, а кнопки под ним гаснут.
     * На отвеченный вопрос второй раз не отвечают — повторный тап ничего не запускает.
     * На имя Яйцо отвечает по имени; на «Не скажу» благодарить не за что.
     *
     * @return принят ли ответ
     */
    suspend fun answerName(name: String?, now: Instant): Boolean {
        if (dao.answer(MascotQuestion.NAME.topic, if (name != null) Choice.YES else Choice.NO) == 0) return false
        val profile = dao.profile() ?: MascotProfileEntity()
        dao.upsertProfile(profile.copy(name = name ?: profile.name, nameAsked = true))
        if (name != null) answer(Topic.NAME_THANKS, Mascot.EGG, now)
        return true
    }

    /** Имя, сменённое со страницы дневника. Спрашивать его больше незачем — человек его назвал. */
    suspend fun setName(name: String) {
        val profile = dao.profile() ?: MascotProfileEntity()
        dao.upsertProfile(profile.copy(name = name, nameAsked = true))
    }

    /** Пол из помощника цели: с ним персонажи говорят «ты ел» и «ты ела» правильно. */
    suspend fun setSex(sex: Sex) {
        val profile = dao.profile() ?: MascotProfileEntity()
        if (profile.sex != sex) dao.upsertProfile(profile.copy(sex = sex))
    }

    /**
     * «Пишите» или «Не надо». Ответ ложится в вопрос, как у имени, и второй раз не принимается:
     * повторный тап по «Пишите» не спрашивает телефон заново.
     *
     * На «Не надо» Зёрнышко отвечает сразу, на «Пишите» — после ответа телефона, см. [notificationsResult].
     *
     * @return принят ли ответ
     */
    suspend fun answerWrite(allow: Boolean, now: Instant): Boolean {
        if (dao.answer(MascotQuestion.WRITE.topic, if (allow) Choice.YES else Choice.NO) == 0) return false
        val profile = dao.profile() ?: MascotProfileEntity()
        val consent = if (allow) WriteConsent.ALLOWED else WriteConsent.DECLINED
        dao.upsertProfile(profile.copy(consent = consent, consentAt = now.toEpochMilliseconds()))
        if (!allow) answer(Topic.WRITE_NO, Mascot.GRAIN, now)
        return true
    }

    /** Телефон разрешил уведомления или нет. */
    suspend fun notificationsResult(granted: Boolean, now: Instant): MascotMessageEntity? =
        answer(if (granted) Topic.WRITE_YES else Topic.WRITE_DENIED, Mascot.GRAIN, now)

    // --- Рассказывает о фоновой работе -----------------------------------------------------

    /**
     * Фоновая докачка дошла до новой точки — сказать об этом в ленте дня.
     *
     * Иначе человек видит только последствия: вчера поиск не понимал «барщ», сегодня
     * понимает, и ни то ни другое ничем не объяснено.
     *
     * Статус приходит при каждом запуске, поэтому о каждой точке говорят один раз за всю
     * жизнь приложения, а о сбое — раз в день: докачка может падать часами. Докачанное
     * закрывает разговор совсем: обновление приложения тянет пак заново, а человеку это
     * уже не новость. Это ответ в ленте, а не первое слово в шторке: ни дневного
     * бюджета, ни согласия писать первыми он не касается.
     *
     * Персонажи говорят по одному, и новость ждёт своей очереди — см. [awaitTurn]. Ждать
     * бывает долго, поэтому статусы собирают через `collectLatest`: новый статус отменяет
     * ожидание старого, и «докачиваю» не прозвучит, когда уже докачалось.
     *
     * @return сказанное; `null` — говорить не о чем
     */
    suspend fun report(model: BackgroundModel, delivery: ModelDelivery, clock: Clock = Clock.System): MascotMessageEntity? {
        untold(model, delivery, clock.now()) ?: return null
        awaitTurn(clock)
        val now = clock.now()
        // Пока ждали очереди, об этом могли рассказать — сверяемся заново.
        val (topic, mascot) = untold(model, delivery, now) ?: return null
        return answer(topic, mascot, now)
    }

    /**
     * Приложение совсем новое — Масло зовёт написать разработчику, что поправить.
     *
     * Один раз за всю жизнь и по немногу: не в первый день — там знакомство, имя и
     * вопрос про уведомления, и не в день, когда персонажи уже что-то сказали: тогда
     * до другого дня. Очередь та же, что у новостей, — см. [awaitTurn].
     *
     * @return сказанное; `null` — не сегодня или уже говорили
     */
    suspend fun tellAppIsNew(clock: Clock = Clock.System): MascotMessageEntity? {
        if (!appIsNewDue(clock.now())) return null
        awaitTurn(clock)
        val now = clock.now()
        // Пока ждали очереди, сегодня могли заговорить другие — сверяемся заново.
        if (!appIsNewDue(now)) return null
        return answer(Topic.APP_NEW, Mascot.BUTTER, now)
    }

    /**
     * Дневник открывается и с компьютера — Зёрнышко называет адрес страницы.
     *
     * Сам — один раз за всю жизнь: это знакомство с возможностью, а не напоминание.
     * Очередь та же, что у новостей, — см. [awaitTurn]. Дальше адрес — по просьбе,
     * см. [answerWebAddress].
     *
     * @return сказанное; `null` — уже говорили
     */
    suspend fun tellWebAddress(address: String, clock: Clock = Clock.System): MascotMessageEntity? {
        if (dao.lastSaid(Topic.WEB_ADDRESS.name) != null) return null
        awaitTurn(clock)
        // Пока ждали очереди, адрес могли назвать по просьбе — второй раз не нужно.
        if (dao.lastSaid(Topic.WEB_ADDRESS.name) != null) return null
        return answerWebAddress(address, clock.now())
    }

    /** Человек спросил адрес страницы — отвечают сразу и сколько угодно раз. */
    suspend fun answerWebAddress(address: String, now: Instant): MascotMessageEntity? {
        val facts = lightFacts(now).copy(address = address)
        val said = Voices.of(Mascot.GRAIN).compose(Topic.WEB_ADDRESS, facts, dao.recent(MEMORY).memory(), random) ?: return null
        return save(Mascot.GRAIN, Topic.WEB_ADDRESS, said, Delivery.FEED, facts.now.date.toEpochDays(), now)
    }

    /** Адрес спросили, а его нет — телефон не в Wi-Fi: Зёрнышко говорит, что сделать. */
    suspend fun answerNoWifi(now: Instant): MascotMessageEntity? = answer(Topic.WEB_NO_WIFI, Mascot.GRAIN, now)

    private suspend fun appIsNewDue(now: Instant): Boolean {
        if (dao.lastSaid(Topic.APP_NEW.name) != null) return false
        val today = now.toLocalDateTime(zone).date.toEpochDays()
        if (diaryDao.since(today - FOOD_LOOKBACK_DAYS).none { it.dateEpochDay < today }) return false
        return dao.observeDay(today).first().isEmpty()
    }

    /** Новость о докачке, если о ней ещё не рассказывали; `null` — говорить не о чем. */
    private suspend fun untold(model: BackgroundModel, delivery: ModelDelivery, now: Instant): Pair<Topic, Mascot>? {
        val news = news(model, delivery) ?: return null
        val done = if (model == BackgroundModel.SEARCH) Topic.SEARCH_READY else Topic.ASSISTANT_READY
        if (dao.lastSaid(done.name) != null) return null

        val last = dao.lastSaid(news.first.name)?.let(Instant::fromEpochMilliseconds)
        val sameDay = last?.toLocalDateTime(zone)?.date == now.toLocalDateTime(zone).date
        val repeats = delivery == ModelDelivery.FAILED && !sameDay
        if (last != null && !repeats) return null
        return news
    }

    /**
     * Дождаться очереди заговорить о новом.
     *
     * Персонажи говорят по одному. Пока человек ничего не записал, на экране знакомство,
     * и новость его не перебивает. Вопрос держит слово, пока ждёт ответа, — см. [awaitsAnswer].
     * И не вслед за другим: после сообщения, записи или открытия приложения выдерживается
     * пауза, чтобы два сообщения не пришли разом.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitTurn(clock: Clock) {
        val today = clock.now().toLocalDateTime(zone).date.toEpochDays()
        combine(
            dao.observeDay(today),
            // Знакомство — пока за неделю ни одной записи: так же решает сводка дня.
            diaryDao.observeSince(today - INTRO_DAYS + 1),
            profile,
            asking(today),
        ) { said, eaten, profile, asked -> Turn(said, eaten, profile, asked) }
            .mapLatest { (said, eaten, profile, asked) ->
                val lastEntryAt = eaten.maxOfOrNull { it.createdAt } ?: return@mapLatest false
                if (awaitsAnswer(said, lastEntryAt)) return@mapLatest false
                // Просьба держит слово, как и вопрос: пришла после последней записи — ждём ответа.
                if (asked.any { it >= lastEntryAt }) return@mapLatest false
                val lastActivity = maxOf(lastEntryAt, said.maxOfOrNull { it.createdAt } ?: 0L, profile.lastOpenAt ?: 0L)
                val wait = lastActivity + PAUSE_MS - clock.now().toEpochMilliseconds()
                if (wait > 0) delay(wait)
                true
            }
            .first { it }
    }

    /** Всё, от чего зависит очередь заговорить: у типизированного combine нет четвёрки-кортежа. */
    private data class Turn(
        val said: List<MascotMessageEntity>,
        val eaten: List<DiaryEntryEntity>,
        val profile: MascotProfileEntity,
        val asked: List<Long>,
    )

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
     * Еда записана: гордится хозяин макроса. Ответ один — персонажи говорят по одному:
     * пришёл черёд вопроса — звучит он, а хозяин только подпрыгивает. Иначе хозяин
     * говорит, когда есть что сказать: новинка, третий день подряд, ночь, первая запись
     * дня; иначе — изредка.
     */
    private suspend fun ate(entry: DiaryEntryEntity, now: Instant): Reaction {
        val owner = entry.nutriments().owner()
        val day = entry.dateEpochDay
        val history = diaryDao.since(day - FOOD_LOOKBACK_DAYS).filter { it.id != entry.id }
        if (askIfDue(day, entriesToday = history.count { it.dateEpochDay == day } + 1, now)) {
            return Reaction(Cue(CueKind.PROUD, owner), null)
        }
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

    /**
     * Спросить, если пришёл черёд: имя — на первую запись дня, разрешение писать — после
     * имени и со второй записи. Только про сегодня и по вопросу за раз: заданный сегодня
     * второй раз в тот же день не задаётся, а следующий ждёт ответа на него.
     *
     * @return задан ли вопрос
     */
    private suspend fun askIfDue(day: Long, entriesToday: Int, now: Instant): Boolean {
        if (day != now.toLocalDateTime(zone).date.toEpochDays()) return false
        val profile = dao.profile() ?: MascotProfileEntity()
        val question = when {
            profile.name == null && !profile.nameAsked -> MascotQuestion.NAME
            entriesToday >= 2 && profile.asksToWrite(now) -> MascotQuestion.WRITE
            else -> return false
        }
        if (dao.observeDay(day).first().any { it.question == question }) return false
        val at = now.toEpochMilliseconds()
        dao.insert(
            MascotMessageEntity(
                mascot = question.mascot,
                topic = question.topic,
                text = question.text,
                lineIds = "",
                delivery = Delivery.FEED,
                dateEpochDay = day,
                createdAt = at,
                // Задан на глазах у человека, в ответ на его запись: в переписку шторки не просится.
                openedAt = at,
            )
        )
        return true
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

        /** Окно знакомства: за столько дней ни одной записи — сводка дня снова представляет троих. */
        const val INTRO_DAYS = 7

        /** Тишина в переписке перед новостью: сообщение не приходит вслед за другим. */
        const val PAUSE_MS = 60_000L
    }
}

/**
 * Кто и на какую тему рассказывает о докачке: Яйцо докладывает, Масло утешает, когда
 * застряло, Зёрнышко радуется. Нейросеть качает система, и ждать Wi-Fi ей не о чем.
 */
private fun news(model: BackgroundModel, delivery: ModelDelivery): Pair<Topic, Mascot>? = when (model) {
    BackgroundModel.SEARCH -> when (delivery) {
        ModelDelivery.DOWNLOADING -> Topic.SEARCH_LOADING to Mascot.EGG
        ModelDelivery.WAITING_FOR_WIFI -> Topic.SEARCH_WAITING_WIFI to Mascot.BUTTER
        ModelDelivery.FAILED -> Topic.SEARCH_FAILED to Mascot.BUTTER
        ModelDelivery.READY -> Topic.SEARCH_READY to Mascot.GRAIN
    }
    BackgroundModel.ASSISTANT -> when (delivery) {
        ModelDelivery.DOWNLOADING -> Topic.ASSISTANT_LOADING to Mascot.EGG
        ModelDelivery.WAITING_FOR_WIFI -> null
        ModelDelivery.FAILED -> Topic.ASSISTANT_FAILED to Mascot.BUTTER
        ModelDelivery.READY -> Topic.ASSISTANT_READY to Mascot.GRAIN
    }
}

/** Почта разработчика: её называет Масло, когда зовёт рассказать, что поправить. */
const val FEEDBACK_EMAIL = "fate.i30mb1@gmail.com"

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
