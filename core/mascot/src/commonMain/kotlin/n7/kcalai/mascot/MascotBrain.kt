package n7.kcalai.mascot

import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
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
import n7.kcalai.model.PageGrace
import n7.kcalai.model.Sex
import n7.kcalai.model.Walk
import n7.kcalai.model.WalkTotals
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

    /** Слово берут по одному: пока один ждёт очереди и говорит, остальные ждут за ним, см. [inTurn]. */
    private val floor = Mutex()

    /** Профиль; пока его строки нет — профиль по умолчанию: ничего не спрашивали, ничего не знаем. */
    val profile: Flow<MascotProfileEntity> = dao.observeProfile().map { it ?: MascotProfileEntity() }

    fun messages(dateEpochDay: Long): Flow<List<MascotMessageEntity>> =
        dao.observeDay(dateEpochDay).map { said -> said.filter { it.hiddenAt == null } }

    /**
     * Человек скрыл сообщение корзинкой: из переписки — в приложении и на странице, — но не из памяти:
     * персонажи по-прежнему знают, что это уже сказано, и не повторят. Вопрос без ответа не скрывается:
     * он ждёт ответа и держит слово.
     */
    suspend fun hide(messageId: Long, now: Instant) {
        val message = dao.findById(messageId) ?: return
        if (message.question != null && message.choice == null) return
        dao.hide(messageId, now.toEpochMilliseconds())
    }

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

    /** Сколько держать страницу дневника, когда телефон убран, — выбирают на странице. */
    suspend fun setPageGrace(grace: PageGrace) {
        val profile = dao.profile() ?: MascotProfileEntity()
        if (profile.pageGrace != grace) dao.upsertProfile(profile.copy(pageGrace = grace))
    }

    /**
     * Цель шагов и рост — из шита шагов, тапом по плашке шагов в ленте.
     *
     * @param heightCm рост; `null` — не назвали, остаётся прежний
     */
    suspend fun setSteps(goal: Int, heightCm: Int?) {
        val profile = dao.profile() ?: MascotProfileEntity()
        dao.upsertProfile(profile.copy(stepsGoal = goal, heightCm = heightCm ?: profile.heightCm))
    }

    /** Рост из помощника цели: второй раз его не спрашивают, по нему же меряется шаг на дорожке. */
    suspend fun setHeight(heightCm: Int) {
        val profile = dao.profile() ?: MascotProfileEntity()
        if (profile.heightCm != heightCm) dao.upsertProfile(profile.copy(heightCm = heightCm))
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

    /**
     * Спросить про беговую дорожку — один раз за всю жизнь, в свою очередь, см. [inTurn].
     * Дальше подключение — по слову «дорожка».
     *
     * @return заданный вопрос; `null` — уже спрашивали
     */
    suspend fun askTreadmill(clock: Clock = Clock.System): MascotMessageEntity? = askOnce(MascotQuestion.TREADMILL, clock)

    /** Задать вопрос один раз за всю жизнь — в свою очередь, как новости. */
    private suspend fun askOnce(question: MascotQuestion, clock: Clock): MascotMessageEntity? {
        if (dao.lastSaid(question.topic) != null) return null
        return inTurn(clock) {
            if (dao.lastSaid(question.topic) != null) null else insertQuestion(question, clock.now())
        }
    }

    /** Вопрос — в ленту, сообщением своего персонажа. */
    private suspend fun insertQuestion(question: MascotQuestion, now: Instant): MascotMessageEntity {
        val message = MascotMessageEntity(
            mascot = question.mascot,
            topic = question.topic,
            text = question.text,
            lineIds = "",
            delivery = Delivery.FEED,
            dateEpochDay = now.toLocalDateTime(zone).date.toEpochDays(),
            createdAt = now.toEpochMilliseconds(),
        )
        return message.copy(id = dao.insert(message))
    }

    /**
     * «Подключить» или «Не надо» на вопрос про дорожку. Ответ ложится в вопрос и второй раз не
     * принимается. На «Не надо» Зёрнышко отвечает сразу, на «Подключить» — когда телефон выберет
     * дорожку, см. [treadmillResult].
     *
     * @return принят ли ответ
     */
    suspend fun answerTreadmill(yes: Boolean, now: Instant): Boolean {
        if (dao.answer(MascotQuestion.TREADMILL.topic, if (yes) Choice.YES else Choice.NO) == 0) return false
        if (!yes) answer(Topic.TREADMILL_NO, Mascot.GRAIN, now)
        return true
    }

    /** Телефон выбрал дорожку и получил Bluetooth — или нет. */
    suspend fun treadmillResult(connected: Boolean, now: Instant): MascotMessageEntity? =
        answer(if (connected) Topic.TREADMILL_YES else Topic.TREADMILL_FAILED, Mascot.GRAIN, now)

    /** Спросить про шагомер телефона — один раз за всю жизнь, в свою очередь, см. [askTreadmill]. */
    suspend fun askSteps(clock: Clock = Clock.System): MascotMessageEntity? = askOnce(MascotQuestion.STEPS, clock)

    /** «Считать» или «Не надо» про шаги телефона; второй ответ не принимается. На «Считать» отвечают после телефона. */
    suspend fun answerSteps(yes: Boolean, now: Instant): Boolean {
        if (dao.answer(MascotQuestion.STEPS.topic, if (yes) Choice.YES else Choice.NO) == 0) return false
        if (!yes) answer(Topic.STEPS_NO, Mascot.GRAIN, now)
        return true
    }

    /** Телефон дал читать шаги — или нет. */
    suspend fun stepsResult(granted: Boolean, now: Instant): MascotMessageEntity? =
        answer(if (granted) Topic.STEPS_YES else Topic.STEPS_FAILED, Mascot.GRAIN, now)

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
     * Персонажи говорят по одному, и новость ждёт своей очереди — см. [inTurn]. Ждать
     * бывает долго, поэтому статусы собирают через `collectLatest`: новый статус отменяет
     * ожидание старого, и «докачиваю» не прозвучит, когда уже докачалось.
     *
     * @return сказанное; `null` — говорить не о чем
     */
    suspend fun report(model: BackgroundModel, delivery: ModelDelivery, clock: Clock = Clock.System): MascotMessageEntity? {
        untold(model, delivery, clock.now()) ?: return null
        return inTurn(clock) {
            val now = clock.now()
            // Пока ждали очереди, об этом могли рассказать — сверяемся заново.
            untold(model, delivery, now)?.let { (topic, mascot) -> answer(topic, mascot, now) }
        }
    }

    /**
     * Приложение совсем новое — Масло зовёт написать разработчику, что поправить.
     *
     * Один раз за всю жизнь и по немногу: не в первый день — там знакомство, имя и
     * вопрос про уведомления, и не в день, когда персонажи уже что-то сказали: тогда
     * до другого дня. Очередь та же, что у новостей, — см. [inTurn].
     *
     * @return сказанное; `null` — не сегодня или уже говорили
     */
    suspend fun tellAppIsNew(clock: Clock = Clock.System): MascotMessageEntity? {
        if (!appIsNewDue(clock.now())) return null
        return inTurn(clock) {
            val now = clock.now()
            // Пока ждали очереди, сегодня могли заговорить другие — сверяемся заново.
            if (appIsNewDue(now)) answer(Topic.APP_NEW, Mascot.BUTTER, now) else null
        }
    }

    /**
     * Дневник открывается и с компьютера — Зёрнышко предлагает это вопросом: что даёт и «рассказать?».
     * Функции предлагают, а не навязывают: адрес — только после «да», см. [answerWeb]. Пока страницей
     * не пользуются ([used]), предложение повторяется раз в месяц, не чаще. Очередь та же, что у новостей.
     *
     * @param used пользуются ли страницей — спрашивается и до очереди, и после: пока ждали, могли начать
     * @return заданный вопрос; `null` — пользуются или рано
     */
    suspend fun offerWeb(used: suspend () -> Boolean, clock: Clock = Clock.System): MascotMessageEntity? {
        if (used() || !webOfferDue(clock.now())) return null
        return inTurn(clock) {
            // Пока ждали очереди, могли и спросить, и начать пользоваться — тогда не нужно.
            if (used() || !webOfferDue(clock.now())) null else insertQuestion(MascotQuestion.WEB, clock.now())
        }
    }

    private suspend fun webOfferDue(now: Instant): Boolean =
        dao.lastSaid(MascotQuestion.WEB.topic)?.let { now.toEpochMilliseconds() - it >= WEB_OFFER_EVERY.inWholeMilliseconds } ?: true

    /**
     * Ответ на предложение вести дневник с компьютера: «да» — инструкция с адресом (нет Wi-Fi — что сделать),
     * «нет» — без адреса, и как к нему вернуться. Второй ответ не принимается.
     *
     * @param address адрес страницы; `null` — телефон не в Wi-Fi
     * @return принят ли ответ
     */
    suspend fun answerWeb(yes: Boolean, address: String?, now: Instant): Boolean {
        if (dao.answer(MascotQuestion.WEB.topic, if (yes) Choice.YES else Choice.NO) == 0) return false
        when {
            !yes -> answer(Topic.WEB_LATER, Mascot.GRAIN, now)
            address != null -> answerWebAddress(address, now)
            else -> answerNoWifi(now)
        }
        return true
    }

    /**
     * Прогулка по дорожке кончилась — Зёрнышко одной репликой говорит, сколько прошли и сколько
     * осталось до цели шагов (или что цель дня есть). Очередь та же, что у новостей, — см. [inTurn];
     * что о прогулке уже рассказали, помнит сама прогулка.
     *
     * @param day итог шагов дня вместе с этой прогулкой
     */
    suspend fun tellWalk(walk: Walk, day: WalkTotals, goal: Int, clock: Clock = Clock.System): MascotMessageEntity? = inTurn(clock) {
        val now = clock.now()
        val steps = walk.steps.roundToInt()
        val facts = lightFacts(now).copy(
            walk = WalkFacts(steps, walk.distanceM, walk.elapsedS, daySteps = day.steps, stepsLeft = goal - day.steps),
        )
        val topic = if (day.steps >= goal) Topic.WALK_GOAL else Topic.WALK_DONE
        val said = Voices.of(Mascot.GRAIN).compose(topic, facts, dao.recent(MEMORY).memory(), random)
        said?.let { save(Mascot.GRAIN, topic, it, Delivery.FEED, facts.now.date.toEpochDays(), now) }
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
     * Дождаться очереди и сказать — одним действием. Порознь двое дожидались бы одной и той же паузы
     * и заговаривали разом: пустили браузер — и про дорожку, и про шаги спрашивали в одну секунду.
     * Следующий берётся ждать, только когда предыдущий уже сказал, и видит его слова.
     */
    private suspend fun <T> inTurn(clock: Clock, speak: suspend () -> T): T = floor.withLock {
        awaitTurn(clock)
        speak()
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
        if (progressIfDue(day, history, now)) return Reaction(Cue(CueKind.CELEBRATE, null), null)
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

    /**
     * Сказать о прогрессе, если пришёл черёд, — сообщением в ленте, по одному на шаг человека:
     * итог прошлой недели — Масло, раз за неделю; иначе круглый день серии с записями — Яйцо, раз за день.
     * Только про сегодня: запись задним числом — не повод.
     *
     * @param history записи до этой, за [FOOD_LOOKBACK_DAYS] дней
     * @return сказано ли
     */
    private suspend fun progressIfDue(day: Long, history: List<DiaryEntryEntity>, now: Instant): Boolean {
        val local = now.toLocalDateTime(zone)
        if (day != local.date.toEpochDays()) return false
        val monday = day - (local.dayOfWeek.isoDayNumber - 1)
        val summarized = dao.lastSaid(Topic.WEEK_SUMMARY.name)
            ?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date.toEpochDays() >= monday } == true
        if (!summarized) {
            val week = facts.lastWeek(monday)
            if (week != null) return tellProgress(Mascot.BUTTER, Topic.WEEK_SUMMARY, day, now) { it.copy(week = week) }
        }
        if (dao.observeDay(day).first().any { it.topic == Topic.STREAK_DAY.name }) return false
        val days = history.mapTo(HashSet()) { it.dateEpochDay } + day
        val streak = generateSequence(day) { it - 1 }.takeWhile { it in days }.count()
        if (!isStreakMilestone(streak)) return false
        return tellProgress(Mascot.EGG, Topic.STREAK_DAY, day, now) { it.copy(streakDays = streak) }
    }

    private suspend fun tellProgress(mascot: Mascot, topic: Topic, day: Long, now: Instant, tune: (Facts) -> Facts): Boolean {
        val said = Voices.of(mascot).compose(topic, tune(lightFacts(now)), dao.recent(MEMORY).memory(), random) ?: return false
        save(mascot, topic, said, Delivery.FEED, day, now)
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

/**
 * Круглый день серии — повод сказать: третий, неделя, две недели, три, месяц, полсотни, сотня, дальше —
 * каждая сотня. Между ними серия растёт молча: поздравлять каждый день — шум.
 */
internal fun isStreakMilestone(days: Int): Boolean = days in STREAK_MILESTONES || (days > 100 && days % 100 == 0)

private val STREAK_MILESTONES = setOf(3, 7, 14, 21, 30, 50, 100)

/** Не пользуются дневником с компьютера — предложение повторяется не чаще этого. */
private val WEB_OFFER_EVERY = 30.days
