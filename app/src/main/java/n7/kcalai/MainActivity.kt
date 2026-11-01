package n7.kcalai

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.launch
import n7.kcalai.feature.diary.DiaryRoute
import n7.kcalai.feature.diary.DiaryViewModel
import n7.kcalai.notify.MascotNotifier
import n7.kcalai.shake.phoneShakes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.work.ContributionWorker
import n7.kcalai.work.Outbox
import n7.kcalai.work.ScanUploadWorker

class MainActivity : ComponentActivity() {

    private val container get() = (application as KcalApp).container

    /**
     * Экранная модель — у активности, а не внутри экрана: по её просьбе активность
     * спрашивает у телефона разрешение на уведомления и отдаёт ей ответ.
     */
    // Конструктор дневника принимает kotlin.time.Clock — в Kotlin 2.2 он ещё экспериментальный.
    @OptIn(ExperimentalTime::class)
    private val diary: DiaryViewModel by viewModels {
        viewModelFactory {
            initializer {
                DiaryViewModel(
                    diary = container.diaryRepository,
                    resolver = container.textResolver,
                    personal = container.personalRepository,
                    food = container.foodRepository,
                    brain = container.mascotBrain,
                    // Продукт сохранён локально и уже работает; отправку
                    // берёт на себя WorkManager, когда появится сеть.
                    onContributionQueued = { ContributionWorker.enqueue(applicationContext) },
                    onScanFinished = { ScanUploadWorker.enqueue(applicationContext) },
                    // Пузырёк «не отправлено» — только когда есть куда отправлять.
                    outbox = if (container.serverConfigured) Outbox(applicationContext, container) else null,
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
                // Отладка разбора этикетки — инструмент разработчика.
                DiaryRoute(labelDebug = BuildConfig.DEBUG, viewModel = diary)
            }
        }
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
