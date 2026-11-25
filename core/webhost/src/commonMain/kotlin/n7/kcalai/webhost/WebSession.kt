package n7.kcalai.webhost

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import n7.kcalai.database.Choice
import n7.kcalai.database.WebClientDao
import n7.kcalai.database.WebClientEntity
import n7.kcalai.database.admitted
import n7.kcalai.model.DEFAULT_STEPS_GOAL
import n7.kcalai.model.MealType
import n7.kcalai.model.PageGrace
import n7.kcalai.model.TreadmillSample
import n7.kcalai.webapi.ToPage
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WebBrowser
import n7.kcalai.webapi.WebDay
import n7.kcalai.webapi.WebDaySummary
import n7.kcalai.webapi.WebEntry
import n7.kcalai.webapi.WebMacros
import n7.kcalai.webapi.WebPeer
import n7.kcalai.webapi.WebSaid
import n7.kcalai.webapi.WebSuggestion
import n7.kcalai.webapi.WebWalk
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Больше стольких сегодняшних просьб, ждущих ответа, не принимаем.
 *
 * Не защита дневника — в него без тапа и так никто не попадёт, — а защита ленты:
 * иначе любой в той же сети завалит её просьбами.
 */
const val WEB_WAITING_MAX = 3

/** День для страницы: записи и цель того дня, прогулки по дорожке, шаги и что о прогулках сказано. */
data class WebDayContent(
    val entries: List<WebEntry>,
    val goalKcal: Int?,
    val goalMacros: WebMacros? = null,
    val fiberGoal: Int? = null,
    val walks: List<WebWalk> = emptyList(),
    val steps: Int = 0,
    val phoneSteps: Int? = null,
    val stepsGoal: Int = DEFAULT_STEPS_GOAL,
    val heightCm: Int? = null,
    val said: List<WebSaid> = emptyList(),
    val live: TreadmillSample? = null,
)

/**
 * Дневник, каким его видит и правит страница.
 *
 * Запись называется номером подсказки в выдаче на запрос, а не продуктом: продукт
 * знает только телефон, и страница не может подсунуть ему свои числа.
 */
interface WebDiary {

    /** Сегодня по часам телефона: страница своим часам не верит. */
    val today: Long

    fun day(date: Long): Flow<WebDayContent>

    /** Неделя дня [date], с понедельника по воскресенье. */
    fun week(date: Long): Flow<List<WebDaySummary>>

    suspend fun suggest(query: String): List<WebSuggestion>

    /** @param meal приём; `null` — сегодня по часам, в прошлый день — первый незаписанный */
    suspend fun add(query: String, index: Int, grams: Int, day: Long, meal: MealType?)

    suspend fun setGrams(entryId: Long, grams: Int)

    suspend fun delete(entryId: Long)

    /** Цель с сегодня; калории — из граммов. */
    suspend fun setGoal(goal: WebMacros)

    suspend fun setName(name: String)

    /** Сколько держать страницу, когда телефон убран. */
    suspend fun setGrace(grace: PageGrace)

    /** Цель шагов и рост из панели шагов; рост `null` — не трогать. */
    suspend fun setSteps(goal: Int, heightCm: Int?)

    /** Беговая дорожка, подключённая к телефону, прямо сейчас; `null` — её нет на связи. */
    val live: Flow<TreadmillSample?>

    /** Корзинка у реплики персонажа: из переписки, но не из памяти персонажей. */
    suspend fun hideSaid(messageId: Long)
}

/** Сколько пущенных браузеров сейчас на связи — пока хоть один есть, экрану гаснуть нельзя. */
class WebPresence {

    private val count = MutableStateFlow(0)

    val admitted: StateFlow<Int> = count.asStateFlow()

    internal fun enter() = count.update { it + 1 }

    internal fun leave() = count.update { it - 1 }
}

/**
 * Разговор с одним браузером, от первого слова до закрытия вкладки.
 *
 * Браузер, которого телефон не знает, получает код и ждёт: в ленте встаёт вопрос
 * Яйца, и пускает только тап человека. До него браузер не видит дневника и не может
 * ничего записать — это проверяется на каждое сообщение, а не только на странице:
 * страницу можно и подделать. Отозвали — браузер слышит «нет» и больше ничего не видит.
 *
 * @param owner имя хозяина телефона: чей это дневник, видно во вкладке
 * @param label чем назвался браузер — подпись в вопросе Яйца
 */
class WebSession(
    private val clients: WebClientDao,
    private val diary: WebDiary,
    private val owner: Flow<String>,
    private val grace: Flow<PageGrace>,
    private val peers: Flow<List<WebPeer>>,
    private val label: String,
    private val presence: WebPresence,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val newToken: () -> String = ::randomToken,
    private val newCode: () -> String = ::randomCode,
) {

    /** Какой день открыт на странице; сначала — сегодня. */
    private val shown = MutableStateFlow(diary.today)

    /**
     * @param incoming что говорит страница; кончился поток — вкладку закрыли
     * @param send сказать странице
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(incoming: Flow<ToPhone>, send: suspend (ToPage) -> Unit): Unit = coroutineScope {
        val messages = incoming.produceIn(this)
        val hello = messages.receiveCatching().getOrNull() as? ToPhone.Hello
        val token = hello?.let { enter(it.token, send) }
        if (token == null) {
            messages.cancel()
            return@coroutineScope
        }

        val client = clients.observe(token).stateIn(this)
        val watcher = launch {
            var shownDiary = false
            client.map { it.access() }.distinctUntilChanged().collectLatest { access ->
                when (access) {
                    Access.ADMITTED -> {
                        shownDiary = true
                        showDiary(client.value?.id, send)
                    }
                    Access.DENIED -> send(ToPage.Denied)
                    // Пущенного отозвали — с этой или с другой вкладки: дальше он не видит ничего.
                    Access.GONE -> if (shownDiary) send(ToPage.Denied)
                    Access.WAITING -> Unit
                }
            }
        }

        for (message in messages) {
            // Спрашиваем базу на каждое сообщение: пустить могли только что, а отозвать — с другой вкладки.
            if (client.value?.admitted != true) continue
            when (message) {
                is ToPhone.Hello -> Unit
                is ToPhone.Search -> send(ToPage.Suggestions(message.query, diary.suggest(message.query)))
                is ToPhone.Add -> diary.add(message.query, message.index, message.grams, shown.value, message.meal)
                is ToPhone.SetGrams -> diary.setGrams(message.entryId, message.grams)
                is ToPhone.Delete -> diary.delete(message.entryId)
                // В будущее не заглядывают: записывать туда нечего.
                is ToPhone.Open -> shown.value = message.day.coerceAtMost(diary.today)
                is ToPhone.SetGoal -> diary.setGoal(WebMacros(message.prot, message.fat, message.carb))
                is ToPhone.SetName -> diary.setName(message.name)
                is ToPhone.Revoke -> clients.revoke(message.browserId, clock.now().toEpochMilliseconds())
                is ToPhone.SetGrace -> diary.setGrace(message.grace)
                is ToPhone.SetSteps -> diary.setSteps(message.goal, message.heightCm)
                is ToPhone.Hide -> diary.hideSaid(message.messageId)
                ToPhone.Leave -> {
                    clients.leave(token, clock.now().toEpochMilliseconds())
                    break
                }
            }
        }
        watcher.cancel()
    }

    /**
     * Пока браузер пущен и на связи, он держит экран и получает открытый день
     * на каждое изменение — записи, неделю, соседей и пущенные браузеры для настроек.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun showDiary(me: Long?, send: suspend (ToPage) -> Unit) {
        presence.enter()
        try {
            val day = shown.flatMapLatest { date ->
                combine(diary.day(date), diary.week(date), diary.live) { content, week, live ->
                    Triple(date, content.copy(live = live.takeIf { date == diary.today }), week)
                }
            }
            val browsers = clients.observeAdmitted().map { list -> list.map { WebBrowser(it.id, it.label, here = it.id == me) } }
            val profile = combine(owner, grace, ::Pair)
            combine(day, peers, browsers, profile) { (date, content, week), near, admitted, (name, keep) ->
                WebDay(
                    owner = name,
                    entries = content.entries,
                    goalKcal = content.goalKcal,
                    peers = near,
                    goalMacros = content.goalMacros,
                    date = date,
                    today = diary.today,
                    week = week,
                    browsers = admitted,
                    fiberGoal = content.fiberGoal,
                    grace = keep,
                    walks = content.walks,
                    steps = content.steps,
                    phoneSteps = content.phoneSteps,
                    stepsGoal = content.stepsGoal,
                    heightCm = content.heightCm,
                    said = content.said,
                    live = content.live,
                )
            }
                .distinctUntilChanged()
                .collect { send(ToPage.Day(it)) }
        } finally {
            presence.leave()
        }
    }

    /**
     * Узнать браузер или попросить за него.
     *
     * @return токен, под которым он теперь известен; `null` — просить за него нельзя
     */
    private suspend fun enter(token: String?, send: suspend (ToPage) -> Unit): String? {
        val today = clock.todayIn(zone).toEpochDays()
        val known = token?.let { clients.find(it) }
        if (known != null && known.still(today)) {
            if (known.choice == null) send(ToPage.Pairing(known.token, known.code))
            return known.token
        }
        if (clients.waitingCount(today) >= WEB_WAITING_MAX) {
            send(ToPage.Busy)
            return null
        }
        val asked = WebClientEntity(
            token = newToken(),
            code = newCode(),
            label = label,
            dateEpochDay = today,
            createdAt = clock.now().toEpochMilliseconds(),
        )
        clients.insert(asked)
        send(ToPage.Pairing(asked.token, asked.code))
        return asked.token
    }

    private enum class Access { WAITING, ADMITTED, DENIED, GONE }

    private fun WebClientEntity?.access(): Access = when {
        this == null || leftAt != null -> Access.GONE
        choice == Choice.YES -> Access.ADMITTED
        choice == Choice.NO -> Access.DENIED
        else -> Access.WAITING
    }
}

/**
 * Узнанный браузер остаётся собой, пока его пускают: пущен и не вышел — или ждёт
 * ответа сегодня. Отказанный, вышедший и вчерашний, оставшийся без ответа, просятся
 * заново: вчерашнего вопроса в ленте уже не видно, и ответить на него нельзя.
 */
private fun WebClientEntity.still(today: Long): Boolean = when {
    leftAt != null -> false
    choice == Choice.YES -> true
    choice == null -> dateEpochDay == today
    else -> false
}

/** Случайный и длинный: по нему браузер узнают, и угадать его соседу по сети нельзя. */
@OptIn(ExperimentalUuidApi::class)
private fun randomToken(): String = Uuid.random().toHexString()

/** Код только сверяют глазами с вопросом на телефоне, секретом он не служит. */
private fun randomCode(): String = Random.nextInt(CODE_SPACE).toString().padStart(CODE_LENGTH, '0')

private const val CODE_LENGTH = 4
private const val CODE_SPACE = 10_000
