package n7.kcalai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import n7.kcalai.assistant.FoodAssistant
import n7.kcalai.assistant.NanoAssistant
import n7.kcalai.assistant.NanoLabelReader
import n7.kcalai.database.KcalDatabase
import n7.kcalai.database.buildKcalDatabase
import n7.kcalai.feature.diary.OutboxMemory
import n7.kcalai.feature.diary.ScanQueue
import n7.kcalai.feature.scanner.LabelVision
import n7.kcalai.fooddb.installSeed
import n7.kcalai.graph.AppGraph
import n7.kcalai.mascot.MascotShade
import n7.kcalai.model.BackgroundModel
import n7.kcalai.nearby.NearbyDiscovery
import n7.kcalai.nearby.NsdDiscovery
import n7.kcalai.notify.MascotNotifier
import n7.kcalai.semantic.EmbeddingModelInfo
import n7.kcalai.semantic.LiteRtQueryEmbedder
import n7.kcalai.work.PrefsOutboxMemory
import n7.kcalai.work.ScanUploads
import n7.kcalai.repositories.PhoneSteps
import n7.kcalai.walk.RecordedSteps
import n7.kcalai.walk.TreadmillCompanion
import n7.kcalai.walk.TreadmillLink

/**
 * Граф приложения на Android. Что с чем соединено — общий с iOS [AppGraph]; здесь
 * только то, чего на iOS нет или что там своё: база и справочник из контекста,
 * Gemini Nano, поиск по смыслу на LiteRT, шторка, WorkManager-флаги.
 */
class AppContainer(context: Context) : AppGraph() {

    private val appContext = context.applicationContext

    override fun openDatabase(): KcalDatabase = buildKcalDatabase(appContext)
    override fun seedPath(): String = installSeed(appContext).toString()

    /** Debug — из `local.properties`, release — из `~/.gradle/gradle.properties` (`kcal.server=…`). */
    override val serverBaseUrl: String = BuildConfig.KCAL_SERVER

    override val userAgent = "KcalAI/1.0 (Android; n7.kcalai)"

    override fun discovery(): NearbyDiscovery = NsdDiscovery(appContext)

    /** Шагомер телефона — своя запись шагов; без сервисов Google или разрешения шагов телефона нет. */
    val recordedSteps by lazy { RecordedSteps(appContext) }

    override fun phoneSteps(): PhoneSteps = recordedSteps
    override fun shade(): MascotShade = MascotNotifier(appContext)
    override fun outboxMemory(): OutboxMemory = PrefsOutboxMemory(appContext)

    override fun semantic(): Semantic = Semantic(embedder, EmbeddingModelInfo.ID, EmbeddingModelInfo.DIM)

    /** Съёмки этикеток — только у разработчика: людям обещано, что кадры остаются на телефоне. */
    override fun scanQueue(): ScanQueue? = if (silentUploads) ScanUploads(appContext, this) else null

    override val silentUploads: Boolean get() = serverConfigured && BuildConfig.KCAL_SILENT_UPLOADS

    /** Модель поиска по смыслу: и отпечатки запросов, и докачка её пака. */
    private val embedder: LiteRtQueryEmbedder by lazy { LiteRtQueryEmbedder(appContext) }

    /** Живёт дольше экрана: докачка модели не должна обрываться вместе с ним. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val nano: NanoAssistant by lazy { NanoAssistant(appContext, diaryTools, appScope) }

    /** Gemini Nano: есть не на каждом телефоне, и экран спрашивает об этом сам. */
    val assistant: FoodAssistant get() = nano

    /** Gemini Nano по этикетке — когда камера с распознаванием застряли. */
    val labelVision: LabelVision by lazy {
        val reader = NanoLabelReader()
        LabelVision { label -> reader.read(label) }
    }

    /**
     * Старт процесса: фоновая докачка словами персонажей, «приложение новое» и итоги прогулок по дорожке.
     * Слушает всю жизнь процесса: модель докачивается и без экрана, а сказанное
     * лежит в базе и дождётся человека.
     */
    fun startMascots() {
        appScope.launch { mascotRoutines.narrate(BackgroundModel.SEARCH, embedder.delivery) }
        appScope.launch { mascotRoutines.narrate(BackgroundModel.ASSISTANT, nano.delivery.filterNotNull()) }
        appScope.launch { mascotRoutines.onLaunch() }
        appScope.launch { mascotRoutines.tellWalks() }
        appScope.launch { mascotRoutines.askTreadmill(webUsed) }
        // Запись шагов включается сама, если «Физическую активность» уже разрешили.
        appScope.launch { recordedSteps.start() }
        startTreadmill()
    }

    /** Связь с беговой дорожкой — одна на процесс, см. [TreadmillLink]. */
    val treadmillLink: TreadmillLink by lazy { TreadmillLink(appContext, treadmillFeed, appScope) }

    /**
     * Беговая дорожка, выбранная в ленте: с Android 12 система будит приложение, когда она рядом
     * ([n7.kcalai.walk.TreadmillCompanionService]); ниже — связь ждёт её, пока жив процесс.
     */
    fun startTreadmill() {
        val companion = TreadmillCompanion(appContext)
        if (companion.address == null) return
        if (companion.wakes) {
            companion.observe()
            // Дорожка могла включиться раньше приложения: о замеченной система второй раз не скажет.
            treadmillLink.appeared()
        } else {
            treadmillLink.appeared(waiting = true)
        }
    }
}
