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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import n7.kcalai.model.FiberTotal
import n7.kcalai.model.GoalMacros
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.DEFAULT_STEPS_GOAL
import n7.kcalai.model.HEIGHT_CM_RANGE
import n7.kcalai.model.STEPS_GOAL_RANGE
import n7.kcalai.model.PageGrace
import n7.kcalai.model.TreadmillSample
import n7.kcalai.model.owner

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

/** Имя — подпись в чужой ленте и во вкладке, а не документ. */
const val WEB_NAME_MAX = 40

/** Больше килограмма белка, жира или углеводов в день цель не бывает, ноль — тоже не цель. */
const val WEB_GOAL_MAX_G = 1_000

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
    data class Add(val query: String, val index: Int, val grams: Int, val meal: MealType? = null) : ToPhone

    data class SetGrams(val entryId: Long, val grams: Int) : ToPhone

    data class Delete(val entryId: Long) : ToPhone

    /** Показать другой день — из недели или стрелками по неделям. Будущее не открывается. */
    data class Open(val day: Long) : ToPhone

    /** Цель в граммах: калории из них складываются сами, как в шите цели. Действует с сегодня. */
    data class SetGoal(val prot: Int, val fat: Int, val carb: Int) : ToPhone

    data class SetName(val name: String) : ToPhone

    /** Отозвать пущенный браузер — и чужой, забытый в гостях, и свой. */
    data class Revoke(val browserId: Long) : ToPhone

    /** Сколько держать страницу, когда телефон убран. */
    data class SetGrace(val grace: PageGrace) : ToPhone

    /** Цель шагов и рост — из панели шагов. [heightCm] `null` — рост не трогать. */
    data class SetSteps(val goal: Int, val heightCm: Int?) : ToPhone

    /** «Выйти»: телефон забывает этот браузер, и в следующий раз его снова надо пустить. */
    data object Leave : ToPhone

    /** Корзинка у реплики персонажа: убрать её из переписки — и на телефоне тоже. */
    data class Hide(val messageId: Long) : ToPhone

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

    /** Открытый день; приходит заново на каждое его изменение. */
    data class Day(val day: WebDay) : ToPage

    data class Suggestions(val query: String, val items: List<WebSuggestion>) : ToPage
}

/** Пущенный браузер. @param here это тот, в котором открыта страница */
data class WebBrowser(val id: Long, val label: String, val here: Boolean)

/** День недели в полосе над дневником: калории и цель того дня, шаги. */
data class WebDaySummary(val date: Long, val kcal: Int, val goalKcal: Int?, val steps: Int = 0)

/**
 * Прогулка по дорожке. Время — минутами от полуночи по часам телефона: страница своим часам не верит.
 *
 * @param endMinute когда дорожка последний раз о ней сообщила
 */
data class WebWalk(
    val startMinute: Int,
    val endMinute: Int,
    val distanceM: Int,
    val elapsedS: Int,
    val steps: Int,
    val topSpeedKmh: Double,
) {
    /** Средний темп, км/ч. */
    val avgSpeedKmh: Double get() = if (elapsedS > 0) distanceM * KMH_PER_MPS / elapsedS else 0.0
}

/**
 * Реплика персонажа в ленте дня — итог прогулки.
 *
 * @param id сообщение на телефоне — по нему его скрывают корзинкой
 * @param minute когда сказана, от полуночи
 */
data class WebSaid(val id: Long, val mascot: Mascot, val text: String, val minute: Int)

private const val KMH_PER_MPS = 3.6

/**
 * Дневник дня глазами страницы.
 *
 * @param owner как назвался хозяин телефона — чей это дневник, видно во вкладке
 * @param goalKcal цель дня; `null` — не задана
 * @param peers телефоны рядом: у каждого своя страница
 * @param goalMacros цель по белкам, жирам и углеводам — полосы в плитках; `null` — не задана
 * @param date какой это день, эпохальные сутки
 * @param today какой день сегодня по часам телефона: страница своим часам не верит
 * @param week неделя открытого дня, с понедельника по воскресенье
 * @param browsers пущенные браузеры — для настроек; вместе с днём, чтобы не ждать отдельного слова
 * @param fiberGoal клетчатка на день, граммы: своя цель или по калориям цели, как в приложении
 * @param grace сколько страница живёт, когда телефон убран, — для настроек
 * @param walks прогулки дня по дорожке
 * @param steps шаги дня: телефона и дорожки вместе
 * @param phoneSteps из них — насчитанные самим телефоном; `null` — телефон шаги не отдаёт
 * @param stepsGoal цель шагов на день
 * @param heightCm рост из профиля — для панели шагов; `null` — не называли
 * @param said что персонажи сказали в ленте дня о прогулках
 * @param live беговая дорожка, подключённая к телефону, прямо сейчас; `null` — её нет на связи
 */
data class WebDay(
    val owner: String,
    val entries: List<WebEntry>,
    val goalKcal: Int?,
    val peers: List<WebPeer>,
    val goalMacros: WebMacros? = null,
    val date: Long = 0,
    val today: Long = 0,
    val week: List<WebDaySummary> = emptyList(),
    val browsers: List<WebBrowser> = emptyList(),
    val fiberGoal: Int? = null,
    val grace: PageGrace = PageGrace.MIN_5,
    val walks: List<WebWalk> = emptyList(),
    val steps: Int = 0,
    val phoneSteps: Int? = null,
    val stepsGoal: Int = DEFAULT_STEPS_GOAL,
    val heightCm: Int? = null,
    val said: List<WebSaid> = emptyList(),
    val live: TreadmillSample? = null,
) {
    val kcal: Int get() = entries.sumOf { it.kcal }

    fun totals(): NutrimentTotals = entries.totals()

    fun fiber(): FiberTotal = entries.fiber()
}

/** Цель дня в граммах. */
data class WebMacros(val prot: Int, val fat: Int, val carb: Int) {
    val kcal: Int get() = GoalMacros(prot, fat, carb).kcal
}

/**
 * Запись дневника: числа уже посчитаны телефоном на её вес. Белки, жиры и углеводы — в сотых грамма.
 *
 * @param fiberCg клетчатка в сотых грамма; `null` — у продукта она неизвестна, и это не ноль
 * @param time когда записано, по часам телефона: «13:05»
 */
data class WebEntry(
    val id: Long,
    val name: String,
    val grams: Int,
    val meal: MealType,
    val kcal: Int,
    val protCg: Int,
    val fatCg: Int,
    val carbCg: Int,
    val fiberCg: Int? = null,
    val time: String = "",
) {
    val totals: NutrimentTotals get() = NutrimentTotals(kcal, protCg, fatCg, carbCg)

    /** Чей это макрос — по тому же правилу, что точка на чипсе в приложении. */
    val owner: Mascot get() = totals.owner()
}

/** Сумма записей — дня или приёма. */
fun List<WebEntry>.totals(): NutrimentTotals = NutrimentTotals(
    kcal = sumOf { it.kcal },
    protCg = sumOf { it.protCg },
    fatCg = sumOf { it.fatCg },
    carbCg = sumOf { it.carbCg },
)

/** Клетчатка записей: известная — суммой, неизвестная — пробелом, как в шапке приложения. */
fun List<WebEntry>.fiber(): FiberTotal = FiberTotal(cg = sumOf { it.fiberCg ?: 0 }, unknown = count { it.fiberCg == null })

/** @param mascot хозяин макроса продукта — цвет точки на подсказке */
data class WebSuggestion(val name: String, val grams: Int, val kcal: Int, val mascot: Mascot)

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
        is ToPhone.Open -> "open"
        is ToPhone.SetGoal -> "goal"
        is ToPhone.SetName -> "name"
        is ToPhone.Revoke -> "revoke"
        is ToPhone.SetGrace -> "grace"
        is ToPhone.SetSteps -> "steps"
        ToPhone.Leave -> "leave"
        is ToPhone.Hide -> "hide"
    },
) {
    when (message) {
        is ToPhone.Hello -> message.token?.let { put("token", it) }
        is ToPhone.Search -> put("query", message.query)
        is ToPhone.Add -> {
            put("query", message.query)
            put("index", message.index)
            put("grams", message.grams)
            message.meal?.let { put("meal", it.name) }
        }
        is ToPhone.SetGrams -> {
            put("id", message.entryId)
            put("grams", message.grams)
        }
        is ToPhone.Delete -> put("id", message.entryId)
        is ToPhone.Open -> put("day", message.day)
        is ToPhone.SetGoal -> {
            put("prot", message.prot)
            put("fat", message.fat)
            put("carb", message.carb)
        }
        is ToPhone.SetName -> put("name", message.name)
        is ToPhone.Revoke -> put("id", message.browserId)
        is ToPhone.SetGrace -> put("grace", message.grace.name)
        is ToPhone.SetSteps -> {
            put("goal", message.goal)
            message.heightCm?.let { put("height", it) }
        }
        ToPhone.Leave -> Unit
        is ToPhone.Hide -> put("id", message.messageId)
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
            day.goalMacros?.let { goal ->
                put("goalProt", goal.prot)
                put("goalFat", goal.fat)
                put("goalCarb", goal.carb)
            }
            put("entries", buildJsonArray { day.entries.forEach { add(it.toJson()) } })
            put("peers", buildJsonArray { day.peers.forEach { add(it.toJson()) } })
            put("date", day.date)
            put("today", day.today)
            put("week", buildJsonArray { day.week.forEach { add(it.toJson()) } })
            put("browsers", buildJsonArray { day.browsers.forEach { add(it.toJson()) } })
            day.fiberGoal?.let { put("fiberGoal", it) }
            put("grace", day.grace.name)
            put("walks", buildJsonArray { day.walks.forEach { add(it.toJson()) } })
            put("steps", day.steps)
            day.phoneSteps?.let { put("phoneSteps", it) }
            put("stepsGoal", day.stepsGoal)
            day.heightCm?.let { put("height", it) }
            put("said", buildJsonArray { day.said.forEach { add(it.toJson()) } })
            day.live?.let { live ->
                put(
                    "live",
                    buildJsonObject {
                        put("speed", live.speedKmh)
                        put("distance", live.distanceM)
                        put("kcal", live.kcal)
                        put("elapsed", live.elapsedS)
                    },
                )
            }
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
    fiberCg?.let { put("fiber", it) }
    put("time", time)
}

private fun WebDaySummary.toJson() = buildJsonObject {
    put("date", date)
    put("kcal", kcal)
    goalKcal?.let { put("goal", it) }
    put("steps", steps)
}

private fun WebWalk.toJson() = buildJsonObject {
    put("from", startMinute)
    put("to", endMinute)
    put("distance", distanceM)
    put("elapsed", elapsedS)
    put("steps", steps)
    put("top", topSpeedKmh)
}

private fun WebSaid.toJson() = buildJsonObject {
    put("id", id)
    put("mascot", mascot.name)
    put("text", text)
    put("minute", minute)
}

private fun WebBrowser.toJson() = buildJsonObject {
    put("id", id)
    put("label", label)
    put("here", here)
}

private fun WebPeer.toJson() = buildJsonObject {
    put("name", name)
    put("url", url)
}

private fun WebSuggestion.toJson() = buildJsonObject {
    put("name", name)
    put("grams", grams)
    put("kcal", kcal)
    put("mascot", mascot.name)
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
            meal = json.text("meal")?.let { name -> MealType.entries.firstOrNull { it.name == name } ?: return null },
        )
        "grams" -> ToPhone.SetGrams(entryId = json.long("id") ?: return null, grams = json.grams() ?: return null)
        "delete" -> ToPhone.Delete(entryId = json.long("id") ?: return null)
        "open" -> ToPhone.Open(day = json.long("day") ?: return null)
        "goal" -> ToPhone.SetGoal(
            prot = json.goalGrams("prot") ?: return null,
            fat = json.goalGrams("fat") ?: return null,
            carb = json.goalGrams("carb") ?: return null,
        )
        "name" -> ToPhone.SetName(name = json.text("name")?.trim()?.takeIf { it.isNotEmpty() }?.take(WEB_NAME_MAX) ?: return null)
        "revoke" -> ToPhone.Revoke(browserId = json.long("id") ?: return null)
        "grace" -> ToPhone.SetGrace(grace = json.grace() ?: return null)
        "steps" -> ToPhone.SetSteps(
            goal = json.long("goal")?.toInt()?.takeIf { it in STEPS_GOAL_RANGE } ?: return null,
            heightCm = json.long("height")?.toInt()?.let { it.takeIf { cm -> cm in HEIGHT_CM_RANGE } ?: return null },
        )
        "leave" -> ToPhone.Leave
        "hide" -> ToPhone.Hide(messageId = json.long("id") ?: return null)
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
                goalMacros = json.macros(),
                date = json.long("date") ?: return null,
                today = json.long("today") ?: return null,
                week = json.list("week") { it.toSummary() } ?: return null,
                browsers = json.list("browsers") { it.toBrowser() } ?: return null,
                fiberGoal = json.long("fiberGoal")?.toInt(),
                grace = json.grace() ?: return null,
                walks = json.list("walks") { it.toWalk() } ?: return null,
                steps = json.long("steps")?.toInt() ?: return null,
                phoneSteps = json.long("phoneSteps")?.toInt(),
                stepsGoal = json.long("stepsGoal")?.toInt() ?: return null,
                heightCm = json.long("height")?.toInt(),
                said = json.list("said") { it.toSaid() } ?: return null,
                live = (json["live"] as? JsonObject)?.let { it.toLive() ?: return null },
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
    fiberCg = long("fiber")?.toInt(),
    time = text("time") ?: return null,
)

private fun JsonObject.toSummary(): WebDaySummary? = WebDaySummary(
    date = long("date") ?: return null,
    kcal = long("kcal")?.toInt() ?: return null,
    goalKcal = long("goal")?.toInt(),
    steps = long("steps")?.toInt() ?: return null,
)

private fun JsonObject.toWalk(): WebWalk? = WebWalk(
    startMinute = long("from")?.toInt() ?: return null,
    endMinute = long("to")?.toInt() ?: return null,
    distanceM = long("distance")?.toInt() ?: return null,
    elapsedS = long("elapsed")?.toInt() ?: return null,
    steps = long("steps")?.toInt() ?: return null,
    topSpeedKmh = double("top") ?: return null,
)

private fun JsonObject.toLive(): TreadmillSample? = TreadmillSample(
    speedKmh = double("speed") ?: return null,
    distanceM = long("distance")?.toInt() ?: return null,
    kcal = long("kcal")?.toInt() ?: return null,
    elapsedS = long("elapsed")?.toInt() ?: return null,
)

private fun JsonObject.toSaid(): WebSaid? = WebSaid(
    id = long("id") ?: return null,
    mascot = text("mascot")?.let { name -> Mascot.entries.firstOrNull { it.name == name } } ?: return null,
    text = text("text") ?: return null,
    minute = long("minute")?.toInt() ?: return null,
)

private fun JsonObject.toBrowser(): WebBrowser? = WebBrowser(
    id = long("id") ?: return null,
    label = text("label") ?: return null,
    here = (this["here"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null,
)

private fun JsonObject.grace(): PageGrace? = text("grace")?.let { name -> PageGrace.entries.firstOrNull { it.name == name } }

private fun JsonObject.goalGrams(key: String): Int? = long(key)?.takeIf { it in 1..WEB_GOAL_MAX_G }?.toInt()

private fun JsonObject.toPeer(): WebPeer? = WebPeer(name = text("name") ?: return null, url = text("url") ?: return null)

private fun JsonObject.toSuggestion(): WebSuggestion? = WebSuggestion(
    name = text("name") ?: return null,
    grams = long("grams")?.toInt() ?: return null,
    kcal = long("kcal")?.toInt() ?: return null,
    mascot = text("mascot")?.let { name -> Mascot.entries.firstOrNull { it.name == name } } ?: return null,
)

/** Цель целиком или никак: полоса без одной из трёх целей врала бы. */
private fun JsonObject.macros(): WebMacros? = WebMacros(
    prot = long("goalProt")?.toInt() ?: return null,
    fat = long("goalFat")?.toInt() ?: return null,
    carb = long("goalCarb")?.toInt() ?: return null,
)

private fun JsonObject.grams(): Int? = long("grams")?.takeIf { it in 1..WEB_GRAMS_MAX }?.toInt()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

/** Список, где каждый элемент обязан разобраться: запись без цифр хуже, чем отказ целиком. */
private fun <T> JsonObject.list(key: String, item: (JsonObject) -> T?): List<T>? =
    (this[key] as? JsonArray)?.map { element: JsonElement -> (element as? JsonObject)?.let(item) ?: return null }
