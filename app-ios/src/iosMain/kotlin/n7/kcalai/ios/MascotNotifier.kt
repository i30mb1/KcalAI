package n7.kcalai.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import n7.kcalai.mascot.MascotShade
import n7.kcalai.mascot.ShadeReply
import n7.kcalai.mascot.SuggestionExtras
import n7.kcalai.mascot.Written
import n7.kcalai.model.Log
import n7.kcalai.model.Mascot
import n7.kcalai.model.title
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * Шторка как мессенджер: каждый из троих — отдельная переписка.
 *
 * Переписку держит `threadIdentifier`: iOS сам складывает сообщения одного
 * персонажа в одну стопку, и заглушить можно одного, не трогая остальных.
 * Каналов, как на Android, здесь нет — это делает система.
 *
 * Кнопка «Записать: X» — действие категории. Категорий ровно столько, сколько
 * персонажей: текст кнопки у каждого свой, а менять его на лету нельзя —
 * категории регистрируются заранее.
 */
class MascotNotifier : MascotShade {

    private val center = UNUserNotificationCenter.currentNotificationCenter()

    /**
     * Можно ли показать уведомление прямо сейчас.
     *
     * Если нельзя — мозг не пишет вовсе: ненаписанное не тратит дневной бюджет
     * и не считается проигнорированным.
     */
    override suspend fun canPost(): Boolean = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            continuation.resume(
                status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional,
            )
        }
    }

    /** Спросить разрешение. Человек отказал — персонажи остаются в ленте и молчат в шторке. */
    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, error ->
            error?.let { Log.w(TAG, "разрешение на уведомления: ${it.localizedDescription}") }
            continuation.resume(granted)
        }
    }

    /** Сообщение персонажа: непрочитанное за сегодня — одной перепиской, с кнопкой, если есть что записать. */
    override suspend fun post(written: Written) {
        if (!canPost()) return
        val mascot = written.message.mascot

        val content = base(mascot, written.message.text)
        written.suggestion?.let { suggestion ->
            registerLogAction(mascot, suggestion.candidate.displayName)
            content.setCategoryIdentifier(categoryId(mascot))
            content.setUserInfo(
                SuggestionExtras.encode(suggestion, written.message.id, mascot) + (MESSAGE_ID to written.message.id.toString()),
            )
        } ?: content.setUserInfo(mapOf(MESSAGE_ID to written.message.id.toString()))

        show(mascot, content)
    }

    /**
     * Ответ в той же переписке. Стопка персонажа уже держит его сообщение, а запись
     * человек только что сделал сам — показывать стоит один ответ.
     *
     * Без звука: человек только что сам нажал кнопку и смотрит на шторку.
     */
    override suspend fun postReply(reply: ShadeReply) {
        if (!canPost()) return
        val text = reply.reply?.text ?: return
        show(reply.mascot, base(reply.mascot, text).also { it.setSound(null) })
    }

    /** Человек открыл приложение: всё написанное теперь стоит в ленте, а шторку пора освободить. */
    fun cancelAll() {
        center.removeAllDeliveredNotifications()
    }

    private fun base(mascot: Mascot, text: String): UNMutableNotificationContent =
        UNMutableNotificationContent().apply {
            setTitle(mascot.title)
            setBody(text)
            // Своя стопка на каждого: заглушить одного, не трогая остальных.
            setThreadIdentifier(threadId(mascot))
            setSound(UNNotificationSound.defaultSound)
        }

    private fun show(mascot: Mascot, content: UNMutableNotificationContent) {
        // Идентификатор свой у каждого: новое сообщение заменяет прошлое этого
        // персонажа, а не копится стопкой из одинаковых.
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = threadId(mascot),
            content = content,
            trigger = null,
        )
        center.addNotificationRequest(request) { error ->
            error?.let { Log.w(TAG, "не удалось показать сообщение ${mascot.title}: ${it.localizedDescription}") }
        }
    }

    /**
     * Текст кнопки меняется с едой, а категории заводятся заранее — поэтому
     * категория пересоздаётся под каждое сообщение, по одной на персонажа.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun registerLogAction(mascot: Mascot, food: String) {
        val action = UNNotificationAction.actionWithIdentifier(
            identifier = ACTION_LOG,
            title = "Записать: ${food.substringBefore(',').trim()}",
            // Не открывает приложение, как и на Android: человек мог быть посреди чего угодно.
            options = UNNotificationActionOptionNone,
        )
        val category = UNNotificationCategory.categoryWithIdentifier(
            identifier = categoryId(mascot),
            actions = listOf(action),
            intentIdentifiers = emptyList<String>(),
            options = UNNotificationCategoryOptionNone,
        )
        center.setNotificationCategories(setOf(category))
    }

    private fun threadId(mascot: Mascot) = "mascot-${mascot.name.lowercase()}"

    private fun categoryId(mascot: Mascot) = "log-${mascot.name.lowercase()}"

    companion object {
        const val ACTION_LOG = "n7.kcalai.log"
        const val MESSAGE_ID = "message"
        private const val TAG = "MascotNotifier"
    }
}
