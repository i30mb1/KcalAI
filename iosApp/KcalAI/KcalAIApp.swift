import SwiftUI
import Shared

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
        .onChange(of: phase) { _, now in
            if now == .active { KcalApp.shared.onForeground() }
        }
    }
}

private final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
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
}

private struct DiaryView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        KcalApp.shared.viewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
