package n7.kcalai.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import n7.kcalai.assistant.AppleModelBridge
import n7.kcalai.feature.diary.DiaryRoute
import n7.kcalai.feature.diary.LocalScreenAwake
import n7.kcalai.feature.diary.ScreenAwake
import n7.kcalai.ui.KcalTheme
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UserNotifications.UNNotificationDefaultActionIdentifier

/**
 * Приложение целиком: граф, экранная модель, фоновая работа и шторка.
 *
 * Живёт в Kotlin, а не в Swift: там всё это пришлось бы пересказывать заново
 * и следить, чтобы оно не разошлось с Android. Наружу торчит контроллер
 * и функции для `UIApplicationDelegate`.
 */
object KcalApp {

    private val container by lazy { AppContainer() }

    private val background by lazy { BackgroundWork(container) }

    /** Живёт весь процесс: персонажи ждут очереди дольше, чем открыт экран. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Сборка — общая с Android. Съёмок этикеток на iOS нет, и фоном ничего не уходит. */
    private val diary by lazy { container.diaryViewModel(assistant = container.assistant) }

    /**
     * Зовётся из `didFinishLaunching` — до его возврата: обработчики фоновых
     * задач система требует зарегистрировать именно там.
     *
     * @param model нейросеть Apple. `null` — на этом устройстве её нет,
     *        и экран живёт без неё. Передаётся здесь, до сборки экранной модели:
     *        дневник спрашивает про модель один раз.
     */
    fun onLaunch(model: AppleModelBridge?) {
        model?.let(container::useAppleModel)
        background.register()
        background.schedule()
        // «Приложение новое» — тот же общий код, что зовёт старт на Android.
        scope.launch { container.mascotRoutines.onLaunch() }
    }

    /** Человек открыл приложение: написанное теперь стоит в ленте, шторку пора освободить. */
    fun onForeground() {
        container.notifier.cancelAll()
    }

    /**
     * Нажатие в шторке — из `UNUserNotificationCenterDelegate` оболочки.
     *
     * «Записать» пишет еду, не открывая приложение, как на Android; тап по самому
     * сообщению открывает дневник, и персонаж знает, что его прочитали.
     *
     * @param done обязательно вызвать: иначе iOS считает обработку зависшей
     */
    fun onNotificationResponse(action: String, info: Map<Any?, *>, done: () -> Unit) {
        val extras = info.entries.mapNotNull { (key, value) -> (key as? String)?.let { it to value.toString() } }.toMap()
        when (action) {
            MascotNotifier.ACTION_LOG -> scope.launch {
                try {
                    container.mascotRoutines.logFromShade(extras)
                } finally {
                    done()
                }
            }
            UNNotificationDefaultActionIdentifier -> {
                extras[MascotNotifier.MESSAGE_ID]?.toLongOrNull()?.let(diary::onOpenedFromMessage)
                done()
            }
            else -> done()
        }
    }

    fun viewController(): UIViewController = ComposeUIViewController {
        KcalTheme {
            // Тряску ловим, только пока дневник на экране: в кармане она — не игра.
            LaunchedEffect(Unit) {
                phoneShakes().collect { diary.onPhoneShaken() }
            }
            // «Пишите» в ответ Зёрнышку: спрашиваем систему, ответ уходит обратно в дневник.
            LaunchedEffect(Unit) {
                diary.notificationRequests.collect {
                    diary.onNotificationsResult(container.notifier.requestPermission())
                }
            }
            // Браузер на связи со страницей дневника — таймер простоя не гасит экран.
            CompositionLocalProvider(
                LocalScreenAwake provides ScreenAwake { on -> UIApplication.sharedApplication.idleTimerDisabled = on },
            ) {
                DiaryRoute(viewModel = diary)
            }
        }
    }
}
