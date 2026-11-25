package n7.kcalai.mascot

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import n7.kcalai.database.stepsGoalOrDefault
import n7.kcalai.database.MascotMessageEntity
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.BackgroundModel
import n7.kcalai.model.EntrySource
import n7.kcalai.model.Log
import n7.kcalai.model.Mascot
import n7.kcalai.model.ModelDelivery
import n7.kcalai.model.WALK_OVER_MS
import n7.kcalai.model.totals
import n7.kcalai.personal.PersonalRepository
import n7.kcalai.repositories.DiaryRepository
import n7.kcalai.repositories.StepsRepository
import n7.kcalai.repositories.WalkRepository
import n7.kcalai.repositories.toWalk

/**
 * Шторка платформы: как выглядит сообщение персонажа. Что и когда писать — решает общий код.
 */
interface MascotShade {
    /** Можно ли показать уведомление прямо сейчас. Нельзя — мозг не пишет вовсе. */
    suspend fun canPost(): Boolean

    suspend fun post(written: Written)

    /** Ответ в той же переписке после «Записать» прямо из шторки. */
    suspend fun postReply(reply: ShadeReply)
}

/**
 * Переписка после «Записать»: сообщение персонажа, запись человека и ответ.
 *
 * @param logged что записано, словами человека: «Гречка, 200 г»
 */
data class ShadeReply(
    val mascot: Mascot,
    val original: MascotMessageEntity?,
    val logged: String,
    val loggedAt: Long,
    val reply: MascotMessageEntity?,
)

/**
 * Что персонажи делают вне экрана — одним кодом на Android и iOS.
 *
 * Платформа даёт шторку ([MascotShade]) и будильник: WorkManager или BGTaskScheduler
 * зовут [tick], старт приложения — [onLaunch], нажатие «Записать» — [logFromShade].
 * Решает и пишет здесь: появись это в платформенном коде, поведение разъехалось бы.
 *
 * Сбой любого из них — в журнал, а не в падение: персонажи — надстройка,
 * и из-за них нельзя ронять приложение ни на старте, ни в фоне.
 */
class MascotRoutines(
    private val brain: MascotBrain,
    private val diary: DiaryRepository,
    private val personal: PersonalRepository,
    private val shade: MascotShade,
    private val walks: WalkRepository,
    private val steps: StepsRepository,
    private val zone: TimeZone,
    private val clock: Clock = Clock.System,
) {

    /** Фоновое пробуждение: есть ли повод написать первыми. Чаще всего — нет. */
    suspend fun tick() = guarded("персонажи не смогли написать") {
        brain.write(clock.now(), shade.canPost())?.let { shade.post(it) }
    }

    /** Старт приложения. Ждёт своей очереди, поэтому зовётся в фоне, а не на пути к экрану. */
    suspend fun onLaunch() = guarded("не удалось сказать, что приложение новое") {
        brain.tellAppIsNew(clock)
    }

    /**
     * Фоновая докачка — словами персонажей, см. [MascotBrain.report]. `collectLatest`: новый
     * статус отменяет ожидание прошлого, и «докачиваю» не прозвучит, когда уже докачалось.
     */
    suspend fun narrate(model: BackgroundModel, deliveries: Flow<ModelDelivery>) {
        deliveries.collectLatest { delivery ->
            guarded("не удалось рассказать про $model: $delivery") { brain.report(model, delivery, clock) }
        }
    }

    /**
     * Прогулки по дорожке: кончилась — дорожка молчит дольше [WALK_OVER_MS] — и Зёрнышко одной репликой
     * рассказывает итог, см. [MascotBrain.tellWalk]. `collectLatest`: новый пакет той же прогулки отменяет
     * ожидание, и итог не прозвучит посреди ходьбы. Прогулка прошлых дней (телефон был выключен) не
     * рассказывается — помечается рассказанной молча: вчерашний итог сегодня — уже не новость.
     */
    suspend fun tellWalks() {
        walks.observeLast().collectLatest { last ->
            if (last == null || last.toldAt != null) return@collectLatest
            val wait = last.seenAt + WALK_OVER_MS - clock.now().toEpochMilliseconds()
            if (wait > 0) delay(wait)
            guarded("не удалось рассказать о прогулке") {
                val now = clock.now()
                val today = now.toLocalDateTime(zone).date.toEpochDays()
                // «до цели» — по шагам дня вместе: телефона и дорожки
                val day = walks.day(last.dateEpochDay).totals().copy(steps = steps.day(last.dateEpochDay).total)
                val goal = brain.profile.first().stepsGoalOrDefault
                val told = last.dateEpochDay != today || brain.tellWalk(last.toWalk(), day, goal, clock) != null
                if (told) walks.markTold(last, clock.now().toEpochMilliseconds())
            }
        }
    }

    /**
     * Спросить про беговую дорожку — когда дневник впервые открыли с компьютера: прогулка с дорожкой
     * видна на странице дневника. Спрашивают один раз за всю жизнь, см. [MascotBrain.askTreadmill].
     *
     * @param webUsed пущен ли в дневник хоть один браузер
     */
    suspend fun askTreadmill(webUsed: Flow<Boolean>) {
        webUsed.first { it }
        guarded("не удалось спросить про дорожку") { brain.askTreadmill(clock) }
    }

    /**
     * Спросить про шагомер телефона — тоже тем, кто открывает дневник с компьютера: шаги видны на странице.
     * Только если у телефона он есть ([available]); один раз за всю жизнь, см. [MascotBrain.askSteps].
     */
    suspend fun askSteps(available: Boolean, webUsed: Flow<Boolean>) {
        if (!available) return
        webUsed.first { it }
        guarded("не удалось спросить про шаги") { brain.askSteps(clock) }
    }

    /**
     * «Записать: гречка» прямо из шторки, не открывая приложение: еда ложится обычной
     * порцией, а персонаж отвечает в той же переписке — как друг, которому написали «ага, поел».
     *
     * @param extras то, что шторка привезла с нажатием, см. [SuggestionExtras]
     * @return записано ли
     */
    suspend fun logFromShade(extras: Map<String, String>): Boolean {
        val decoded = SuggestionExtras.decode(extras) ?: return false
        val candidate = decoded.suggestion.candidate
        val grams = candidate.servingG ?: return false
        return guarded("запись из шторки не удалась") {
            val now = clock.now()
            diary.add(
                candidate = candidate,
                grams = grams,
                meal = decoded.suggestion.meal,
                date = now.toLocalDateTime(zone).date.toEpochDays(),
                source = EntrySource.MANUAL,
                now = now.toEpochMilliseconds(),
            )
            personal.invalidate()

            brain.markOpened(decoded.messageId, now)
            val reply = brain.answer(Topic.LOGGED, decoded.mascot, now, food = candidate.displayName)
            shade.postReply(
                ShadeReply(
                    mascot = decoded.mascot,
                    original = brain.message(decoded.messageId),
                    logged = "${candidate.displayName}, $grams г",
                    loggedAt = now.toEpochMilliseconds(),
                    reply = reply,
                )
            )
        } != null
    }

    private suspend fun <T> guarded(failure: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.w(TAG, failure, error)
        null
    }

    private companion object {
        const val TAG = "MascotRoutines"
    }
}
