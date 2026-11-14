package n7.kcalai.ios

import androidx.compose.ui.window.ComposeUIViewController
import n7.kcalai.feature.diary.DiaryRoute
import n7.kcalai.feature.diary.DiaryViewModel
import n7.kcalai.ui.KcalTheme
import platform.UIKit.UIViewController

/**
 * Единственное, что оболочка на Swift знает о приложении.
 *
 * Граф и экранная модель живут здесь, а не в Swift: там им пришлось бы
 * пересказывать типы Kotlin, а смысла в этом нет — наружу торчит контроллер.
 */
fun mainViewController(): UIViewController {
    val container = AppContainer()
    val diary = DiaryViewModel(
        diary = container.diaryRepository,
        resolver = container.textResolver,
        personal = container.personalRepository,
        food = container.foodRepository,
        brain = container.mascotBrain,
    )
    return ComposeUIViewController { KcalTheme { DiaryRoute(viewModel = diary) } }
}
