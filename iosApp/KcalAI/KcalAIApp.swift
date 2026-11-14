import SwiftUI
import KcalApp

/// Всё приложение — один контроллер из общего кода.
///
/// Swift здесь только оболочка: граф зависимостей, экранная модель и вёрстка
/// живут в Kotlin и общие с Android. Поэтому тут нет ни состояния, ни логики —
/// иначе они разошлись бы с андроидными.
@main
struct KcalAIApp: App {
    var body: some Scene {
        WindowGroup {
            DiaryView()
                // Персонажи и лента рисуются во весь экран, вместе с «чёлкой»:
                // на Android ровно это делает enableEdgeToEdge.
                .ignoresSafeArea(.all)
                // Клавиатура не поднимает ленту: строка ввода следит за ней сама,
                // как на Android, — иначе содержимое дёргалось бы дважды.
                .ignoresSafeArea(.keyboard)
        }
    }
}

private struct DiaryView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
