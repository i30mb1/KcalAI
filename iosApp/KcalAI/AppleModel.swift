import Foundation
import Shared

#if canImport(FoundationModels)
import FoundationModels
#endif

/// Нейросеть Apple на телефоне — для общего кода.
///
/// Foundation Models живёт только в Swift: заголовков Objective-C у фреймворка
/// нет вовсе, и Kotlin его не видит. Поэтому граница проведена с другой стороны —
/// `AppleModelBridge` объявлен в Kotlin и реализован здесь.
///
/// Мост тонкий намеренно: инструкции, запросы и разбор ответа — в общем коде,
/// те же, что у Gemini Nano на Android. Здесь только «вот инструкция и запрос — ответь».
@available(iOS 26.0, *)
final class AppleModel: NSObject, AssistantAppleModelBridge {

    /// Разговор живёт между вопросами: человек спрашивает «а белка сколько?»
    /// следом за предыдущим вопросом и ждёт, что его поймут.
    private var conversation: LanguageModelSession?

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

    func respond(instructions: String, prompt: String, onResult: @escaping (String?) -> Void) {
        Task {
            // Своя сессия на каждый разбор: он не должен тянуть за собой переписку о дневнике.
            let session = LanguageModelSession(instructions: instructions)
            do {
                onResult(try await session.respond(to: prompt).content)
            } catch {
                // Не поняла или отказалась — для экрана это «ничего не предложено».
                onResult(nil)
            }
        }
    }

    func chat(instructions: String, prompt: String, onChunk: @escaping (String) -> Void, onDone: @escaping () -> Void) {
        let session = conversation ?? LanguageModelSession(instructions: instructions)
        conversation = session
        Task {
            do {
                var sent = ""
                for try await piece in session.streamResponse(to: prompt) {
                    // Поток отдаёт ответ целиком на каждом шаге, а общий код ждёт
                    // кусками: шлём только дописанное.
                    let whole = piece.content
                    onChunk(whole.hasPrefix(sent) ? String(whole.dropFirst(sent.count)) : whole)
                    sent = whole
                }
            } catch {
                // Молча: лента уже показывает то, что успело прийти.
            }
            // Всегда — иначе лента ждала бы ответа вечно.
            onDone()
        }
    }
}
