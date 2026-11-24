package n7.kcalai

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import n7.kcalai.feature.diary.DiaryRoute
import n7.kcalai.feature.diary.LocalScreenAwake
import n7.kcalai.feature.diary.LocalShareText
import n7.kcalai.feature.diary.ScreenAwake
import n7.kcalai.feature.diary.ShareText
import n7.kcalai.feature.diary.DiaryViewModel
import n7.kcalai.feature.scanner.LocalLabelVision
import androidx.compose.runtime.CompositionLocalProvider
import n7.kcalai.notify.MascotNotifier
import n7.kcalai.shake.phoneShakes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.work.ContributionWorker
import n7.kcalai.work.ScanUploadWorker

class MainActivity : ComponentActivity() {

    private val container get() = (application as KcalApp).container

    /**
     * Экранная модель — у активности, а не внутри экрана: по её просьбе активность
     * спрашивает у телефона разрешение на уведомления и отдаёт ей ответ.
     */
    private val diary: DiaryViewModel by viewModels {
        viewModelFactory {
            initializer {
                // Сборка — общая с iOS. Своё здесь только WorkManager: у разработчика он шлёт
                // сам, когда появится сеть; у людей отправить предлагает Яйцо.
                container.diaryViewModel(
                    assistant = container.assistant,
                    onContributionQueued = { if (container.silentUploads) ContributionWorker.enqueue(applicationContext) },
                    onScanFinished = { if (container.silentUploads) ScanUploadWorker.enqueue(applicationContext) },
                )
            }
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        diary.onNotificationsResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                diary.notificationRequests.collect { requestNotifications() }
            }
        }
        lifecycleScope.launch {
            // Тряску ловим, только пока дневник на экране: в кармане она — не игра.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                phoneShakes(this@MainActivity).collect { diary.onPhoneShaken() }
            }
        }
        // При пересоздании активности тот же intent приходит снова — это не новый тап.
        if (savedInstanceState == null) openedFromNotification(intent)

        setContent {
            KcalTheme {
                // Нейросеть читает этикетку, когда камера застряла, — если она есть на телефоне.
                CompositionLocalProvider(
                    LocalLabelVision provides container.labelVision,
                    // Браузер на связи со страницей дневника — окно не даёт экрану гаснуть.
                    LocalScreenAwake provides screenAwake,
                    // Адрес страницы дневника — себе в мессенджер, через системное окно выбора.
                    LocalShareText provides shareText,
                ) {
                    // Отладка разбора этикетки — инструмент разработчика.
                    DiaryRoute(labelDebug = BuildConfig.DEBUG, viewModel = diary)
                }
            }
        }
    }

    private val screenAwake = ScreenAwake { on ->
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private val shareText = ShareText { text ->
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, null))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openedFromNotification(intent)
    }

    override fun onResume() {
        super.onResume()
        // Всё, что писали персонажи, теперь стоит в ленте: шторку пора освободить.
        MascotNotifier(this).cancelAll()
    }

    /** Открыто тапом по сообщению персонажа — пусть он знает, что его прочитали. */
    private fun openedFromNotification(intent: Intent) {
        val messageId = intent.getLongExtra(MascotNotifier.EXTRA_MESSAGE_ID, 0)
        if (messageId > 0) diary.onOpenedFromMessage(messageId)
        intent.removeExtra(MascotNotifier.EXTRA_MESSAGE_ID)
    }

    /**
     * На Android 13 и новее уведомления — разрешение, и его спрашивает система.
     * Ниже спрашивать нечего: есть только выключатель в настройках, и ответ — его положение.
     */
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            diary.onNotificationsResult(NotificationManagerCompat.from(this).areNotificationsEnabled())
        }
    }
}
