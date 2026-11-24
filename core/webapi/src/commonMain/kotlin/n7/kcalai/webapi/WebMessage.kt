package n7.kcalai.webapi

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import n7.kcalai.model.MealType

/*
 * Разговор страницы дневника в браузере с телефоном — по WebSocket в домашней сети.
 *
 * Один код по обе стороны сокета: телефон и страница собраны из этого модуля, и формат
 * не может разъехаться молча. Но страница — это вкладка, открытая когда угодно, и
 * в сокет может постучаться что угодно из той же сети, поэтому разбор не верит ничему:
 * чужая версия, мусор и невозможные числа дают `null`, а не исключение.
 */

/** Версия разговора. Чужую не разбираем: понять наполовину хуже, чем промолчать. */
const val WEB_VERSION = 1

/** Путь сокета на телефоне. */
const val WEB_SOCKET_PATH = "/ws"

/** Больше десяти килограммов за раз человек не ест, а ноль записывать нечего. */
const val WEB_GRAMS_MAX = 10_000

/** Что страница просит у телефона. */
sealed interface ToPhone {

    /**
     * Первое слово страницы.
     *
     * @param token чем браузер назвался в прошлый раз; `null` — он здесь впервые
     */
    data class Hello(val token: String?) : ToPhone

    /** Подсказки к набранному — тот же поиск, что у строки ввода в приложении. */
    data class Search(val query: String) : ToPhone

    /**
     * Записать подсказку.
     *
     * Подсказка называется номером в выдаче на [query], а не продуктом: продукт
     * знает только телефон, и страница не может подсунуть ему чужие числа.
     */
    data class Add(val query: String, val index: Int, val grams: Int) : ToPhone

    data class SetGrams(val entryId: Long, val grams: Int) : ToPhone

    data class Delete(val entryId: Long) : ToPhone

    /** «Выйти»: телефон забывает этот браузер, и в следующий раз его снова надо пустить. */
    data object Leave : ToPhone
}

/** Что телефон говорит странице. */
sealed interface ToPage {

    /**
     * Браузер новый: телефон спрашивает человека, пустить ли его.
     *
     * @param token запомнить сразу — по нему телефон узнает браузер, когда его пустят
     * @param code тот же код стоит в вопросе на телефоне: так видно, что пускаешь свой
     */
    data class Pairing(val token: String, val code: String) : ToPage

    /** На телефоне ответили «Нет». */
    data object Denied : ToPage

    /** Пустить просятся слишком многие сразу — новых не спрашиваем. */
    data object Busy : ToPage

    /** Сегодняшний дневник; приходит заново на каждое его изменение. */
    data class Day(val day: WebDay) : ToPage

    data class Suggestions(val query: String, val items: List<WebSuggestion>) : ToPage
}

/**
 * Дневник дня глазами страницы.
 *
 * @param owner как назвался хозяин телефона — чей это дневник, видно во вкладке
 * @param goalKcal цель дня; `null` — не задана
 * @param peers телефоны рядом: у каждого своя страница
 */
data class WebDay(
    val owner: String,
    val entries: List<WebEntry>,
    val goalKcal: Int?,
    val peers: List<WebPeer>,
) {
    val kcal: Int get() = entries.sumOf { it.kcal }
}

/** Запись дневника: числа уже посчитаны телефоном на её вес. Белки, жиры и углеводы — в сотых грамма. */
data class WebEntry(
    val id: Long,
    val name: String,
    val grams: Int,
    val meal: MealType,
    val kcal: Int,
    val protCg: Int,
    val fatCg: Int,
    val carbCg: Int,
)

data class WebSuggestion(val name: String, val grams: Int, val kcal: Int)

/** Телефон рядом. Имя объявлено его владельцем и ничем не подтверждено. */
data class WebPeer(val name: String, val url: String)

// --- В текст ---------------------------------------------------------------------------

fun encode(message: ToPhone): String = envelope(
    when (message) {
        is ToPhone.Hello -> "hello"
        is ToPhone.Search -> "search"
        is ToPhone.Add -> "add"
        is ToPhone.SetGrams -> "grams"
        is ToPhone.Delete -> "delete"
        ToPhone.Leave -> "leave"
    },
) {
    when (message) {
        is ToPhone.Hello -> message.token?.let { put("token", it) }
        is ToPhone.Search -> put("query", message.query)
        is ToPhone.Add -> {
            put("query", message.query)
            put("index", message.index)
            put("grams", message.grams)
        }
        is ToPhone.SetGrams -> {
            put("id", message.entryId)
            put("grams", message.grams)
        }
        is ToPhone.Delete -> put("id", message.entryId)
        ToPhone.Leave -> Unit
    }
}

fun encode(message: ToPage): String = envelope(
    when (message) {
        is ToPage.Pairing -> "pairing"
        ToPage.Denied -> "denied"
        ToPage.Busy -> "busy"
        is ToPage.Day -> "day"
        is ToPage.Suggestions -> "suggestions"
    },
) {
    when (message) {
        is ToPage.Pairing -> {
            put("token", message.token)
            put("code", message.code)
        }
        ToPage.Denied, ToPage.Busy -> Unit
        is ToPage.Day -> {
            val day = message.day
            put("owner", day.owner)
            day.goalKcal?.let { put("goal", it) }
            put("entries", buildJsonArray { day.entries.forEach { add(it.toJson()) } })
            put("peers", buildJsonArray { day.peers.forEach { add(it.toJson()) } })
        }
        is ToPage.Suggestions -> {
            put("query", message.query)
            put("items", buildJsonArray { message.items.forEach { add(it.toJson()) } })
        }
    }
}

private fun envelope(type: String, body: JsonObjectBuilder.() -> Unit): String = buildJsonObject {
    put("v", WEB_VERSION)
    put("t", type)
    body()
}.toString()

private fun WebEntry.toJson() = buildJsonObject {
    put("id", id)
    put("name", name)
    put("grams", grams)
    put("meal", meal.name)
    put("kcal", kcal)
    put("prot", protCg)
    put("fat", fatCg)
    put("carb", carbCg)
}

private fun WebPeer.toJson() = buildJsonObject {
    put("name", name)
    put("url", url)
}

private fun WebSuggestion.toJson() = buildJsonObject {
    put("name", name)
    put("grams", grams)
    put("kcal", kcal)
}

// --- Из текста -------------------------------------------------------------------------

/** Сообщение страницы; `null` — разбирать нечего. Не бросает: из сети приходит всякое. */
fun parseToPhone(text: String): ToPhone? {
    val (type, json) = open(text) ?: return null
    return when (type) {
        "hello" -> ToPhone.Hello(token = json.text("token"))
        "search" -> ToPhone.Search(query = json.text("query") ?: return null)
        "add" -> ToPhone.Add(
            query = json.text("query") ?: return null,
            index = json.long("index")?.toInt()?.takeIf { it >= 0 } ?: return null,
            grams = json.grams() ?: return null,
        )
        "grams" -> ToPhone.SetGrams(entryId = json.long("id") ?: return null, grams = json.grams() ?: return null)
        "delete" -> ToPhone.Delete(entryId = json.long("id") ?: return null)
        "leave" -> ToPhone.Leave
        else -> null
    }
}

/** Сообщение телефона; `null` — разбирать нечего. */
fun parseToPage(text: String): ToPage? {
    val (type, json) = open(text) ?: return null
    return when (type) {
        "pairing" -> ToPage.Pairing(token = json.text("token") ?: return null, code = json.text("code") ?: return null)
        "denied" -> ToPage.Denied
        "busy" -> ToPage.Busy
        "day" -> ToPage.Day(
            WebDay(
                owner = json.text("owner") ?: return null,
                entries = json.list("entries") { it.toEntry() } ?: return null,
                goalKcal = json.long("goal")?.toInt(),
                peers = json.list("peers") { it.toPeer() } ?: return null,
            )
        )
        "suggestions" -> ToPage.Suggestions(
            query = json.text("query") ?: return null,
            items = json.list("items") { it.toSuggestion() } ?: return null,
        )
        else -> null
    }
}

/** Тип и тело; `null` — не JSON-объект или чужая версия. */
private fun open(text: String): Pair<String, JsonObject>? {
    val json = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (error: SerializationException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    } ?: return null
    if (json.long("v") != WEB_VERSION.toLong()) return null
    return (json.text("t") ?: return null) to json
}

private fun JsonObject.toEntry(): WebEntry? = WebEntry(
    id = long("id") ?: return null,
    name = text("name") ?: return null,
    grams = long("grams")?.toInt() ?: return null,
    meal = text("meal")?.let { name -> MealType.entries.firstOrNull { it.name == name } } ?: return null,
    kcal = long("kcal")?.toInt() ?: return null,
    protCg = long("prot")?.toInt() ?: return null,
    fatCg = long("fat")?.toInt() ?: return null,
    carbCg = long("carb")?.toInt() ?: return null,
)

private fun JsonObject.toPeer(): WebPeer? = WebPeer(name = text("name") ?: return null, url = text("url") ?: return null)

private fun JsonObject.toSuggestion(): WebSuggestion? = WebSuggestion(
    name = text("name") ?: return null,
    grams = long("grams")?.toInt() ?: return null,
    kcal = long("kcal")?.toInt() ?: return null,
)

private fun JsonObject.grams(): Int? = long("grams")?.takeIf { it in 1..WEB_GRAMS_MAX }?.toInt()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

/** Список, где каждый элемент обязан разобраться: запись без цифр хуже, чем отказ целиком. */
private fun <T> JsonObject.list(key: String, item: (JsonObject) -> T?): List<T>? =
    (this[key] as? JsonArray)?.map { element: JsonElement -> (element as? JsonObject)?.let(item) ?: return null }
