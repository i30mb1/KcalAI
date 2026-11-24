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
import n7.kcalai.webapi.ToPage
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WebDay
import n7.kcalai.webapi.WebEntry
import n7.kcalai.webapi.WebPeer
import n7.kcalai.webapi.WebSuggestion
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

/** Сегодняшний дневник для страницы: записи и цель. */
data class WebToday(val entries: List<WebEntry>, val goalKcal: Int?)

/**
 * Дневник, каким его видит и правит страница. Всё — о сегодняшнем дне.
 *
 * Запись называется номером подсказки в выдаче на запрос, а не продуктом: продукт
 * знает только телефон, и страница не может подсунуть ему свои числа.
 */
interface WebDiary {

    fun today(): Flow<WebToday>

    suspend fun suggest(query: String): List<WebSuggestion>

    suspend fun add(query: String, index: Int, grams: Int)

    suspend fun setGrams(entryId: Long, grams: Int)

    suspend fun delete(entryId: Long)
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
 * страницу можно и подделать.
 *
 * @param owner имя хозяина телефона: чей это дневник, видно во вкладке
 * @param label чем назвался браузер — подпись в вопросе Яйца
 */
class WebSession(
    private val clients: WebClientDao,
    private val diary: WebDiary,
    private val owner: String,
    private val peers: Flow<List<WebPeer>>,
    private val label: String,
    private val presence: WebPresence,
    private val clock: Clock = Clock.System,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val newToken: () -> String = ::randomToken,
    private val newCode: () -> String = ::randomCode,
) {

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
            client.map { it.access() }.distinctUntilChanged().collectLatest { access ->
                when (access) {
                    Access.ADMITTED -> showDiary(send)
                    Access.DENIED -> send(ToPage.Denied)
                    Access.WAITING, Access.GONE -> Unit
                }
            }
        }

        for (message in messages) {
            // Спрашиваем базу на каждое сообщение: пустить могли только что, а выйти — с другой вкладки.
            if (client.value?.admitted != true) continue
            when (message) {
                is ToPhone.Hello -> Unit
                is ToPhone.Search -> send(ToPage.Suggestions(message.query, diary.suggest(message.query)))
                is ToPhone.Add -> diary.add(message.query, message.index, message.grams)
                is ToPhone.SetGrams -> diary.setGrams(message.entryId, message.grams)
                is ToPhone.Delete -> diary.delete(message.entryId)
                ToPhone.Leave -> {
                    clients.leave(token, clock.now().toEpochMilliseconds())
                    break
                }
            }
        }
        watcher.cancel()
    }

    /** Пока браузер пущен и на связи, он держит экран и получает дневник на каждое изменение. */
    private suspend fun showDiary(send: suspend (ToPage) -> Unit) {
        presence.enter()
        try {
            combine(diary.today(), peers) { today, near -> WebDay(owner, today.entries, today.goalKcal, near) }
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
