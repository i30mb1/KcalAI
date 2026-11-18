import SwiftUI
import Shared
import UserNotifications

/// Оболочка: всё содержательное — в общем коде, вместе с Android.
///
/// Здесь нет ни состояния, ни логики намеренно: появись они тут, их пришлось бы
/// держать в согласии с андроидной половиной вручную.
@main
struct KcalAIApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            DiaryView()
                // Персонажи и лента рисуются во весь экран, вместе с «чёлкой»:
                // на Android ровно это делает enableEdgeToEdge.
                .ignoresSafeArea(.all)
                // Строка ввода следит за клавиатурой сама, как на Android, —
                // иначе содержимое подвинулось бы дважды.
                .ignoresSafeArea(.keyboard)
        }
        // Одним параметром, а не двумя: вариант с «было и стало» появился в iOS 17,
        // а приложение живёт с 16-й.
        .onChange(of: phase) { now in
            if now == .active { KcalApp.shared.onForeground() }
        }
    }
}

private final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Нажатия в шторке приходят сюда — и сразу уходят в общий код.
        // Делегат ставится до возврата: иначе нажатие, которым приложение запустили, потеряется.
        UNUserNotificationCenter.current().delegate = self
        // Обработчики фоновых задач система требует зарегистрировать до возврата отсюда.
        // Нейросеть — только с iOS 26: раньше Foundation Models не существует,
        // и дневник живёт без неё, как на телефонах без Apple Intelligence.
        var model: AssistantAppleModelBridge?
        if #available(iOS 26.0, *) {
            model = AppleModel(tools: AssistantTools(tools: KcalApp.shared.diaryTools))
        }
        KcalApp.shared.onLaunch(model: model)
        return true
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        KcalApp.shared.onNotificationResponse(
            action: response.actionIdentifier,
            info: response.notification.request.content.userInfo,
            done: completionHandler
        )
    }
}

private struct DiaryView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        KcalApp.shared.viewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
