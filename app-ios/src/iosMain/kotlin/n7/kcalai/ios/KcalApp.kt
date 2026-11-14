package n7.kcalai.ios

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.ComposeUIViewController
import n7.kcalai.assistant.AppleModelBridge
import n7.kcalai.assistant.DiaryTools
import n7.kcalai.feature.diary.DiaryRoute
import n7.kcalai.feature.diary.DiaryViewModel
import n7.kcalai.ui.KcalTheme
import platform.UIKit.UIViewController

/**
 * Приложение целиком: граф, экранная модель, фоновая работа и шторка.
 *
 * Живёт в Kotlin, а не в Swift: там всё это пришлось бы пересказывать заново
 * и следить, чтобы оно не разошлось с Android. Наружу торчит контроллер
 * и две функции для `UIApplicationDelegate`.
 */
object KcalApp {

    private val container by lazy { AppContainer() }

    private val background by lazy { BackgroundWork(container) }

    private val notifier = MascotNotifier()

    /** Инструменты нейросети: их зовёт оболочка на Swift, где и живёт модель. */
    val diaryTools: DiaryTools get() = container.diaryTools

    private val diary by lazy {
        DiaryViewModel(
            diary = container.diaryRepository,
            resolver = container.textResolver,
            personal = container.personalRepository,
            food = container.foodRepository,
            brain = container.mascotBrain,
            assistant = container.assistant,
        )
    }

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
    }

    /** Человек открыл приложение: написанное теперь стоит в ленте, шторку пора освободить. */
    fun onForeground() {
        notifier.cancelAll()
    }

    fun viewController(): UIViewController = ComposeUIViewController {
        KcalTheme {
            // Тряску ловим, только пока дневник на экране: в кармане она — не игра.
            LaunchedEffect(Unit) {
                phoneShakes().collect { diary.onPhoneShaken() }
            }
            DiaryRoute(viewModel = diary)
        }
    }
}
