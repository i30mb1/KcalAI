package n7.kcalai.feature.diary

import kotlinx.coroutines.flow.Flow
import n7.kcalai.database.WebClientDao
import n7.kcalai.database.WebClientEntity

/**
 * Браузеры у страницы дневника на телефоне.
 *
 * @param requests просьбы пустить: в ленте они — вопрос Яйца, ответ — тапом
 * @param online сколько пущенных браузеров сейчас на связи: пока хоть один есть,
 *        экрану гаснуть нельзя — страница живёт, пока открыт дневник
 */
class Browsers(val requests: WebClientDao, val online: Flow<Int>)

/**
 * Просьба браузера словами Яйца.
 *
 * Код — тот же, что на экране браузера: совпал — значит, пускаешь тот, что перед тобой,
 * а не чужой из той же сети. Подпись браузер дал о себе сам, поэтому сверяют по коду.
 */
fun browserAsks(label: String, code: String): String = "$label просится в дневник. Код $code — как на экране?"

/**
 * Просьба держит слово: пришла после последней записи и ждёт ответа. Правило то же,
 * что у предложений соседа, — см. [awaitsOffer].
 */
fun awaitsBrowser(requests: List<WebClientEntity>, lastEntryAt: Long?): Boolean =
    requests.any { it.choice == null && it.createdAt >= (lastEntryAt ?: Long.MIN_VALUE) }

/**
 * Человек спрашивает адрес страницы, а не ищет еду: «компьютер», «с ноутбука», «адрес?».
 *
 * Строка целиком, а не слово в ней: «адрес гречки» — уже что-то другое.
 */
fun asksForPage(text: String): Boolean {
    val words = text.lowercase().replace(PUNCTUATION, " ").split(' ').filter(String::isNotBlank)
    return words.isNotEmpty() && words.size <= 2 && words.last() in PAGE_WORDS && words.dropLast(1).all { it in PREPOSITIONS }
}

private val PUNCTUATION = Regex("""[?!.,:;]""")

private val PREPOSITIONS = setOf("с", "со", "на", "через", "в")

private val PAGE_WORDS = setOf(
    "компьютер", "компьютера", "компьютере", "комп", "компа",
    "ноутбук", "ноутбука", "ноутбуке", "ноут", "ноута",
    "браузер", "браузера", "браузере",
    "сайт", "сайта", "сайте",
    "адрес",
)
