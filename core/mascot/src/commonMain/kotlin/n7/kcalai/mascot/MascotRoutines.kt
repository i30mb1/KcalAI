package n7.kcalai.mascot

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import n7.kcalai.database.MascotMessageEntity
import n7.kcalai.mascot.voice.Topic
import n7.kcalai.model.BackgroundModel
import n7.kcalai.model.EntrySource
import n7.kcalai.model.Log
import n7.kcalai.model.Mascot
import n7.kcalai.model.ModelDelivery
import n7.kcalai.personal.PersonalRepository
import n7.kcalai.repositories.DiaryRepository

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
