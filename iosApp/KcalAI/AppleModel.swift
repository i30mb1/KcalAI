import Foundation
import Shared

#if canImport(FoundationModels)
import FoundationModels
#endif

/// Нейросеть Apple на телефоне — для общего кода.
///
/// Foundation Models живёт только в Swift: заголовков Objective-C у фреймворка
/// нет вовсе, и Kotlin его не видит. Поэтому граница проведена с другой стороны —
/// `AppleModelBridge` объявлен в Kotlin и реализован здесь; через неё ходят
/// только строки и колбэки.
///
/// Цифры КБЖУ модель не называет никогда. Она отвечает на те же два вопроса,
/// что и поиск, — «что это» и «сколько грамм», — а продукт берётся из локальной
/// базы по ключу, который выдал поиск. Считает приложение, модель формулирует.
@available(iOS 26.0, *)
final class AppleModel: NSObject, AssistantAppleModelBridge {

    private let tools: AssistantTools

    /// Разговор живёт между вопросами: человек спрашивает «а белка сколько?»
    /// следом за предыдущим вопросом и ждёт, что его поймут.
    private lazy var chat = LanguageModelSession(instructions: Self.instructions)

    init(tools: AssistantTools) {
        self.tools = tools
    }

    /// `KotlinBoolean`, а не `Bool`: через границу с Kotlin примитивы едут в обёртке.
    func isReady(onResult: @escaping (KotlinBoolean) -> Void) {
        #if canImport(FoundationModels)
        if case .available = SystemLanguageModel.default.availability {
            onResult(true)
        } else {
            // Нет Apple Intelligence, выключена или модель ещё качается —
            // для экрана это один и тот же случай: модели сейчас нет.
            onResult(false)
        }
        #else
        onResult(false)
        #endif
    }

    func propose(text: String, onResult: @escaping (String?) -> Void) {
        Task {
            // Своя сессия на разбор фразы: она не должна тянуть за собой
            // переписку о дневнике, а та — сбиваться на разбор.
            let session = LanguageModelSession(instructions: Self.proposeInstructions)
            let found = await tools.search(text)
            let prompt = """
            Фраза: \(text)
            Найденные продукты:
            \(found)
            """
            do {
                let answer = try await session.respond(to: prompt)
                onResult(answer.content)
            } catch {
                // Не поняла или отказалась — для экрана это «ничего не предложено».
                onResult(nil)
            }
        }
    }

    func answer(question: String, onChunk: @escaping (String) -> Void, onDone: @escaping () -> Void) {
        Task {
            do {
                let facts = await tools.diary()
                let stream = chat.streamResponse(to: "Дневник:\n\(facts)\n\nВопрос: \(question)")
                var sent = ""
                for try await piece in stream {
                    // Поток отдаёт ответ целиком на каждом шаге, а общий код ждёт
                    // кусками: шлём только дописанное.
                    let whole = piece.content
                    if whole.hasPrefix(sent) {
                        onChunk(String(whole.dropFirst(sent.count)))
                    } else {
                        onChunk(whole)
                    }
                    sent = whole
                }
            } catch {
                // Молча: лента уже показывает то, что успело прийти.
            }
            // Всегда — иначе лента ждала бы ответа вечно.
            onDone()
        }
    }

    private static let instructions = """
    Ты — помощник в дневнике питания. Отвечай коротко, по-русски, одним-двумя \
    предложениями. Считать нельзя: все числа бери из того, что дано. \
    Не давай медицинских советов и не запрещай еду.
    """

    private static let proposeInstructions = """
    Тебе дают фразу о еде и список найденных продуктов с ключами. \
    Ответь только JSON вида [{"key":"...","grams":120}] — без пояснений. \
    Ключи бери строго из списка: выдумывать ключи нельзя. \
    Если подходящего нет, ответь [].
    """
}

/// Инструменты приложения для модели: поиск и итоги дневника.
///
/// Обёртка над общим `DiaryTools` — тем же, что на Android: ключи, которые
/// выдал поиск, запоминает он, и предложение с чужим ключом не пройдёт.
final class AssistantTools {
    private let tools: AssistantDiaryTools

    init(tools: AssistantDiaryTools) {
        self.tools = tools
    }

    func search(_ query: String) async -> String {
        (try? await tools.searchFood(query: query)) ?? ""
    }

    func diary() async -> String {
        (try? await tools.diaryDays(days: 7)) ?? ""
    }
}
