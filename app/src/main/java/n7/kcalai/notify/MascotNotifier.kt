package n7.kcalai.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import n7.kcalai.MainActivity
import n7.kcalai.R
import n7.kcalai.database.MascotMessageEntity
import n7.kcalai.mascot.Written
import n7.kcalai.mascot.voice.foodName
import n7.kcalai.model.Mascot
import n7.kcalai.model.title
import n7.kcalai.ui.mascot.mascotPortrait

/**
 * Шторка как мессенджер: каждый из троих — отдельная переписка со своей аватаркой.
 *
 * Уведомление — «разговор» в смысле Android: стиль переписки, собеседник-персона
 * и долгоживущий ярлык. Так оно попадает в раздел разговоров шторки, его можно
 * сделать приоритетным или заглушить одного персонажа, не трогая остальных.
 */
class MascotNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Можно ли показать уведомление прямо сейчас.
     *
     * Если нельзя — мозг не пишет вовсе: ненаписанное не тратит дневной бюджет
     * и не считается проигнорированным.
     */
    fun canPost(): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!permitted || !manager.areNotificationsEnabled()) return false
        ensureChannel()
        return manager.getNotificationChannelCompat(CHANNEL)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** Сообщение персонажа: непрочитанное за сегодня — одной перепиской, с кнопкой, если есть что записать. */
    fun post(written: Written) {
        if (!canPost()) return
        val mascot = written.message.mascot
        val author = person(mascot)
        pushShortcut(mascot, author)

        val style = NotificationCompat.MessagingStyle(me())
        written.thread.forEach { style.addMessage(it.text, it.createdAt, author) }

        val builder = base(mascot, style).setContentIntent(open(mascot, written.message.id))
        written.suggestion?.let { suggestion ->
            val extras = SuggestionExtras.encode(suggestion, written.message.id, mascot)
            builder.addAction(0, "Записать: ${shortFood(suggestion.candidate.displayName)}", log(mascot, extras))
        }
        notify(mascot, builder)
    }

    /**
     * Ответ в той же переписке: сообщение персонажа, «запись» человека и ответ.
     *
     * Без звука: человек только что сам нажал кнопку и смотрит на шторку.
     */
    fun postReply(mascot: Mascot, original: MascotMessageEntity?, logged: String, loggedAt: Long, reply: MascotMessageEntity?) {
        if (!canPost()) return
        val author = person(mascot)
        val style = NotificationCompat.MessagingStyle(me())
        original?.let { style.addMessage(it.text, it.createdAt, author) }
        // Автор null — сам человек, «Ты» из стиля переписки.
        style.addMessage(logged, loggedAt, null as Person?)
        reply?.let { style.addMessage(it.text, it.createdAt, author) }

        notify(
            mascot,
            base(mascot, style)
                .setContentIntent(open(mascot, original?.id ?: 0))
                .setOnlyAlertOnce(true)
                .setSilent(true),
        )
    }

    /** Человек открыл приложение: всё написанное теперь стоит в ленте, а шторку пора освободить. */
    fun cancelAll() {
        Mascot.entries.forEach { manager.cancel(notificationId(it)) }
    }

    private fun base(mascot: Mascot, style: NotificationCompat.MessagingStyle): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mascot)
            .setColor(mascot.accent)
            .setStyle(style)
            .setShortcutId(shortcutId(mascot))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)

    @Suppress("MissingPermission") // разрешение проверено в canPost
    private fun notify(mascot: Mascot, builder: NotificationCompat.Builder) {
        manager.notify(notificationId(mascot), builder.build())
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Сообщения от персонажей")
                .setDescription("Яйцо, Масло и Зёрнышко пишут первыми — не чаще пары раз в день")
                .build()
        )
    }

    private fun person(mascot: Mascot): Person = Person.Builder()
        .setName(mascot.title)
        .setKey(shortcutId(mascot))
        .setIcon(icon(mascot))
        .setImportant(true)
        .build()

    private fun me(): Person = Person.Builder().setName("Ты").build()

    private fun icon(mascot: Mascot): IconCompat =
        IconCompat.createWithBitmap(mascotPortrait(mascot, PORTRAIT_PX).asAndroidBitmap())

    /** Долгоживущий ярлык-разговор: без него шторка не считает уведомление перепиской. */
    private fun pushShortcut(mascot: Mascot, author: Person) {
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(mascot))
            .setShortLabel(mascot.title)
            .setLongLived(true)
            .setPerson(author)
            .setIcon(icon(mascot))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    /** Тап по уведомлению открывает дневник — и персонаж знает, на какое сообщение откликнулись. */
    private fun open(mascot: Mascot, messageId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        mascot.ordinal,
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_MESSAGE_ID, messageId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun log(mascot: Mascot, extras: Map<String, String>): PendingIntent = PendingIntent.getBroadcast(
        context,
        LOG_REQUEST + mascot.ordinal,
        Intent(context, LogSuggestionReceiver::class.java).apply { extras.forEach { (key, value) -> putExtra(key, value) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val EXTRA_MESSAGE_ID = "n7.kcalai.notify.MESSAGE_ID"
        private const val CHANNEL = "mascots"
        private const val PORTRAIT_PX = 192
        private const val LOG_REQUEST = 100

        /** Номер уведомления — по персонажу: новое сообщение того же продолжает его переписку. */
        private fun notificationId(mascot: Mascot): Int = NOTIFICATION_BASE + mascot.ordinal
        private const val NOTIFICATION_BASE = 7_000

        private fun shortcutId(mascot: Mascot): String = "mascot-${mascot.name.lowercase()}"

        /** Цвет акцента — макрос персонажа: в шторке видно, кто пишет, ещё до имени. */
        private val Mascot.accent: Int
            get() = when (this) {
                Mascot.EGG -> 0xFFC2456F.toInt()
                Mascot.BUTTER -> 0xFFC8862B.toInt()
                Mascot.GRAIN -> 0xFF4C8F5A.toInt()
            }

        /** Подпись кнопки короткая: «Записать: творог мягкий обезжиренный» шторка обрежет на полуслове. */
        private fun shortFood(name: String): String = foodName(name).let { if (it.length > 18) it.take(17).trimEnd() + "…" else it }
    }
}
