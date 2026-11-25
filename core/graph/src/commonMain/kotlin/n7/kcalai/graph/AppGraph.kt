package n7.kcalai.graph

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import n7.kcalai.assistant.DiaryTools
import n7.kcalai.assistant.FoodAssistant
import n7.kcalai.database.KcalDatabase
import n7.kcalai.database.heightOrDefault
import n7.kcalai.database.stepsGoalOrDefault
import n7.kcalai.model.StepsSettings
import n7.kcalai.feature.diary.Browsers
import n7.kcalai.feature.diary.DiaryOutbox
import n7.kcalai.feature.diary.DiaryViewModel
import n7.kcalai.feature.diary.NearbyPeers
import n7.kcalai.feature.diary.NearbyPeersOverLan
import n7.kcalai.feature.diary.OutboxMemory
import n7.kcalai.feature.diary.ScanQueue
import n7.kcalai.feature.diary.ServerOutbox
import n7.kcalai.fooddb.FoodDb
import n7.kcalai.fooddb.FoodDbFactory
import n7.kcalai.mascot.FactsBuilder
import n7.kcalai.mascot.MascotBrain
import n7.kcalai.mascot.MascotRoutines
import n7.kcalai.mascot.MascotShade
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.nearby.DatabaseOfferInbox
import n7.kcalai.nearby.LAN_PORT
import n7.kcalai.nearby.pageUrl
import n7.kcalai.nearby.NearbyDiscovery
import n7.kcalai.nearby.OfferReceiver
import n7.kcalai.personal.PersonalRepository
import n7.kcalai.remote.ChainedProductSource
import n7.kcalai.remote.ContributionUploader
import n7.kcalai.remote.KcalServerSource
import n7.kcalai.remote.OffProductSource
import n7.kcalai.remote.ScanUploader
import n7.kcalai.remote.SeedSource
import n7.kcalai.remote.ServerProbe
import n7.kcalai.remote.VkusVillProductSource
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.repositories.FoodDbSource
import n7.kcalai.repositories.FoodRepository
import n7.kcalai.repositories.PersonalSemantic
import n7.kcalai.repositories.QueryEmbedder
import n7.kcalai.repositories.RemoteProductSource
import n7.kcalai.repositories.NoPhoneSteps
import n7.kcalai.repositories.PhoneSteps
import n7.kcalai.repositories.StepsRepository
import n7.kcalai.repositories.TreadmillFeed
import n7.kcalai.repositories.WalkRepository
import n7.kcalai.resolver.TextFoodResolver
import n7.kcalai.resolver.parseSegment
import n7.kcalai.webapi.WebPeer
import n7.kcalai.webapi.WebSaid
import n7.kcalai.webhost.DiaryWebHost
import n7.kcalai.webhost.RepositoryWebDiary
import n7.kcalai.webhost.ResourceWebBundle
import n7.kcalai.webhost.WebPresence

/**
 * Граф зависимостей приложения — один на Android и iOS.
 *
 * Раньше у каждой платформы был свой контейнер, и они расходились: в `DiaryRepository`
 * добавился аргумент, а iOS-копию забыли, и она перестала собираться. Теперь что с чем
 * соединено, решается здесь, а платформа наследует граф и даёт только своё —
 * абстрактные члены ниже. Они зовутся лениво, при первом обращении к тому, что их ждёт.
 *
 * Dagger появится, когда мультибиндинг станет нужнее, чем прозрачность ручной сборки.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class AppGraph {

    // --- Что даёт платформа ---------------------------------------------------------------

    /** База дневника: Room открывается по-своему на каждой платформе. */
    protected abstract fun openDatabase(): KcalDatabase

    /** Справочник, развёрнутый из ассетов, — путь к файлу. */
    protected abstract fun seedPath(): String

    /** Адрес своего сервера; пусто — сервера нет, всё остаётся на устройстве. */
    protected abstract val serverBaseUrl: String

    /** Open Food Facts режет запросы без описательного User-Agent: платформа в нём своя. */
    protected abstract val userAgent: String

    /** Поиск соседей в общей сети: NSD на Android, Bonjour на iOS. */
    protected abstract fun discovery(): NearbyDiscovery

    /** Шторка: как выглядит сообщение персонажа. */
    protected abstract fun shade(): MascotShade

    /** Где помнится «Не сейчас» у предложения отправить продукты. */
    protected abstract fun outboxMemory(): OutboxMemory

    /** Поиск по смыслу; `null` — модели отпечатков на этой платформе нет, ищется по словам. */
    protected open fun semantic(): Semantic? = null

    /** Съёмки этикеток в очереди отправки; `null` — их не шлём. */
    protected open fun scanQueue(): ScanQueue? = null

    /**
     * Уходит ли всё само, в фоне. Только у разработчика: в сборке для людей продукт
     * уходит с согласия, по предложению Яйца.
     */
    open val silentUploads: Boolean get() = false

    /** Модель отпечатков: она сама и что в базе лежит под её именем. */
    class Semantic(val embedder: QueryEmbedder, val model: String, val dim: Int)

    // --- Общее -----------------------------------------------------------------------------

    val database: KcalDatabase by lazy { openDatabase() }

    /** Задан ли адрес сервера. Без него вклады копятся и ждут, справочник не докачивается. */
    val serverConfigured: Boolean get() = serverBaseUrl.isNotBlank()

    /**
     * Справочник живёт на одном соединении SQLite, а оно не потокобезопасно.
     * Один поток — единственная дисциплина, которая это гарантирует.
     */
    private val foodDbDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val foodDbLock = Mutex()
    private var openedFoodDb: FoodDb? = null

    private val foodDbSource = FoodDbSource {
        openedFoodDb ?: foodDbLock.withLock {
            openedFoodDb ?: FoodDbFactory.open(
                driver = BundledSQLiteDriver(),
                seedPath = seedPath(),
                // Полный срез Open Food Facts докачивается отдельно.
                foodPath = null,
            ).also { openedFoodDb = it }
        }
    }

    private val kcalServer by lazy { KcalServerSource(baseUrl = serverBaseUrl, userAgent = userAgent) }

    /**
     * Свой сервер спрашивается раньше Open Food Facts: в своём пуле лежат российские
     * товары, которых в OFF нет, и только свой ответ мы вправе раздавать дальше — OFF
     * лежит под ODbL. ВкусВилл последний: ему достаются одни настоящие промахи.
     */
    val remoteProductSource: RemoteProductSource by lazy {
        ChainedProductSource(listOf(kcalServer, OffProductSource(userAgent), VkusVillProductSource(userAgent)))
    }

    val contributionUploader: ContributionUploader get() = kcalServer
    val seedSource: SeedSource get() = kcalServer
    val scanUploader: ScanUploader get() = kcalServer
    val serverProbe: ServerProbe get() = kcalServer

    val foodRepository: FoodRepository by lazy {
        val semantic = semantic()
        FoodRepository(
            foodDb = foodDbSource,
            userFoodDao = database.userFoodDao(),
            cachedProductDao = database.cachedProductDao(),
            contributionDao = database.contributionDao(),
            remote = remoteProductSource,
            dispatcher = foodDbDispatcher,
            embedder = semantic?.embedder,
            personal = semantic?.let {
                PersonalSemantic(
                    dao = database.semanticDao(),
                    model = it.model,
                    dim = it.dim,
                    queryOf = { logged -> parseSegment(logged)?.name },
                )
            },
        )
    }

    val diaryRepository: DiaryRepository by lazy {
        DiaryRepository(database.diaryDao(), database.goalDao(), database.contributionDao())
    }

    /** Прогулки по беговой дорожке, подключённой к телефону. */
    val walkRepository: WalkRepository by lazy {
        WalkRepository(database.walkDao(), TimeZone.currentSystemDefault(), height = { mascotBrain.profile.first().heightOrDefault })
    }

    /** Шагомер самого телефона: Health Connect / Core Motion. Без доступа — шагов телефона нет. */
    open fun phoneSteps(): PhoneSteps = NoPhoneSteps

    /** Шаги дня вместе — телефона и дорожки, без двойного счёта. */
    val stepsRepository: StepsRepository by lazy { StepsRepository(walkRepository, phoneSteps(), TimeZone.currentSystemDefault()) }

    /** Дорожка у телефона: платформа отдаёт сюда пакеты Bluetooth, отсюда — прогулки и живые числа. */
    val treadmillFeed: TreadmillFeed by lazy { TreadmillFeed(walkRepository) }

    /** Пущен ли в дневник хоть один браузер — тогда Зёрнышко спросит про дорожку. */
    val webUsed get() = database.webClientDao().observeAdmitted().map { it.isNotEmpty() }

    /**
     * Зона нужна моделям: `createdAt` хранится в UTC, а привычки у человека
     * в местном времени — завтрак в восемь утра остаётся завтраком и после перелёта.
     */
    val personalRepository: PersonalRepository by lazy {
        PersonalRepository(
            diaryDao = database.diaryDao(),
            personalDao = database.personalDao(),
            bodyMetricDao = database.bodyMetricDao(),
            goalDao = database.goalDao(),
            zone = TimeZone.currentSystemDefault(),
        )
    }

    val textResolver: TextFoodResolver by lazy { TextFoodResolver(foodRepository, personalRepository) }

    /**
     * Инструменты нейросети: поиск по той же базе, что у строки ввода, и итоги дневника.
     * Один экземпляр на приложение: предложения проверяет экран, и помнить, что поиск
     * показывал модели, они должны общее.
     */
    val diaryTools: DiaryTools by lazy {
        DiaryTools(
            search = { query, limit -> foodRepository.search(query, limit) },
            day = { epochDay -> diaryRepository.observeDay(epochDay).first().entries },
            goal = { epochDay -> diaryRepository.goalFor(epochDay) },
            today = { Clock.System.todayIn(TimeZone.currentSystemDefault()).toEpochDays() },
        )
    }

    /** Мозг Яйца, Масла и Зёрнышка. Всё — по дневнику и на телефоне: факты в сеть не уходят. */
    val mascotBrain: MascotBrain by lazy {
        val zone = TimeZone.currentSystemDefault()
        MascotBrain(
            dao = database.mascotDao(),
            facts = FactsBuilder(database.diaryDao(), database.goalDao(), personalRepository, zone),
            diaryDao = database.diaryDao(),
            zone = zone,
            // Просьбы соседа и браузера ждут ответа так же, как вопросы персонажей: новость молчит.
            asking = { day ->
                combine(database.peerOfferDao().observeDay(day), database.webClientDao().observeDay(day)) { offers, browsers ->
                    offers.filter { it.choice == null }.map { it.createdAt } +
                        browsers.filter { it.choice == null }.map { it.createdAt }
                }
            },
        )
    }

    /** Что персонажи делают вне экрана. Платформа зовёт это из своих будильников. */
    val mascotRoutines: MascotRoutines by lazy {
        MascotRoutines(
            brain = mascotBrain,
            diary = diaryRepository,
            personal = personalRepository,
            shade = shade(),
            walks = walkRepository,
            steps = stepsRepository,
            zone = TimeZone.currentSystemDefault(),
        )
    }

    /**
     * Телефоны рядом. Приёмник и объявление живут, пока открыт дневник; имя — из профиля:
     * пока человек не назвался, фича молчит. На том же сервере — страница дневника.
     */
    private val lan: NearbyPeersOverLan by lazy {
        NearbyPeersOverLan(
            discovery = discovery(),
            receiver = OfferReceiver(
                inbox = DatabaseOfferInbox(database.peerOfferDao()),
                port = LAN_PORT,
                routes = diaryWebHost,
            ),
            myName = mascotBrain.profile.map { it.name },
            // Сервер страницы живёт в области приложения: ушёл экран — держится выбранный запас.
            scope = lanScope,
            grace = mascotBrain.profile.map { it.pageGrace.keep },
        )
    }

    /** Где живёт сервер страницы дневника: дольше экрана — на выбранный запас. */
    private val lanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val nearbyPeers: NearbyPeers get() = lan

    /** Сколько пущенных браузеров на связи со страницей дневника. */
    private val webPresence = WebPresence()

    /**
     * Страница дневника для браузера в той же сети. Пишет тем же путём, что строка ввода:
     * тот же поиск, тот же снимок КБЖУ, та же память порций при правке граммов.
     */
    private val diaryWebHost: DiaryWebHost by lazy {
        DiaryWebHost(
            clients = database.webClientDao(),
            diary = RepositoryWebDiary(
                diary = diaryRepository,
                walks = walkRepository,
                steps = stepsRepository,
                walkSaid = ::walkSaid,
                live = treadmillFeed.live,
                hide = { id -> mascotBrain.hide(id, Clock.System.now()) },
                suggestFood = { query -> textResolver.suggest(query) },
                regram = personalRepository::onGramsEdited,
                rename = mascotBrain::setName,
                keepPage = mascotBrain::setPageGrace,
                stepsGoal = mascotBrain.profile.map { StepsSettings(it.stepsGoalOrDefault, it.heightCm) },
                saveSteps = mascotBrain::setSteps,
            ),
            owner = mascotBrain.profile.map { it.name },
            grace = mascotBrain.profile.map { it.pageGrace },
            // Лениво: соседей знает сервер, который эту страницу и отдаёт.
            peers = flow { emitAll(lan.lanPeers) }.map { peers -> peers.map { WebPeer(it.name, pageUrl(it.host, it.port)) } },
            presence = webPresence,
            bundle = ResourceWebBundle,
        )
    }

    /** Что Зёрнышко сказало о прогулках в ленте дня — страница показывает это в своей ленте. */
    private fun walkSaid(day: Long) = mascotBrain.messages(day).map { said ->
        val zone = TimeZone.currentSystemDefault()
        said.filter { it.topic == Topic.WALK_DONE.name || it.topic == Topic.WALK_GOAL.name }.map {
            val at = Instant.fromEpochMilliseconds(it.createdAt).toLocalDateTime(zone)
            WebSaid(it.id, it.mascot, it.text, minute = at.hour * MINUTES_PER_HOUR + at.minute)
        }
    }

    /** Предложение Яйца отправить продукты. `null` — сервера нет, отправлять некуда. */
    val outbox: DiaryOutbox? by lazy {
        if (!serverConfigured) {
            null
        } else {
            ServerOutbox(
                contributions = database.contributionDao(),
                uploader = contributionUploader,
                probe = serverProbe,
                memory = outboxMemory(),
                scans = scanQueue(),
                retriesInBackground = silentUploads,
            )
        }
    }

    /**
     * Экранная модель дневника — одна сборка на обе платформы.
     *
     * @param assistant нейросеть на телефоне; `null` — её нет, и экран живёт без неё
     * @param onContributionQueued пнуть фоновую отправку после нового продукта
     * @param onScanFinished съёмка этикетки записана — её можно отправлять
     */
    fun diaryViewModel(
        assistant: FoodAssistant?,
        onContributionQueued: () -> Unit = {},
        onScanFinished: () -> Unit = {},
    ): DiaryViewModel = DiaryViewModel(
        diary = diaryRepository,
        resolver = textResolver,
        personal = personalRepository,
        food = foodRepository,
        brain = mascotBrain,
        onContributionQueued = onContributionQueued,
        onScanFinished = onScanFinished,
        outbox = outbox,
        // Предложения соседа: приходят в базу, лента берёт их оттуда.
        peerOffers = database.peerOfferDao(),
        peers = nearbyPeers,
        // Браузеры у страницы дневника: просьбы пустить — в ленту, кто на связи — держит экран.
        browsers = Browsers(database.webClientDao(), webPresence.admitted),
        assistant = assistant,
        // Шаги дня — плашкой в ленте: телефона и дорожки вместе.
        daySteps = stepsRepository::observeDay,
        weekSteps = stepsRepository::observeWeek,
        // Сигнал дорожки ловили — с неё есть хоть одна прогулка.
        treadmillSeen = walkRepository.observeLast().map { it != null },
    )
}

private const val MINUTES_PER_HOUR = 60
