package n7.kcalai.webapi

import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType

/**
 * Что видно на странице дневника в браузере.
 *
 * Логика страницы — здесь, в общем коде, под тестами; страница на Compose только рисует.
 */
sealed interface PageState {

    /** Стучимся в телефон. */
    data object Connecting : PageState

    /** Ждём, пока на телефоне нажмут «Пустить». */
    data class Pairing(val code: String) : PageState

    data object Denied : PageState

    data object Busy : PageState

    /** Телефон не отвечает: дневник на нём закрыт, экран погас или сеть другая. */
    data object Lost : PageState

    /** Браузер вышел сам: телефон его забыл, и страница больше не стучится. */
    data object Left : PageState

    /** @param suggestions последний ответ поиска — к какому запросу он, проверяет [suggestionsFor] */
    data class Diary(val day: WebDay, val suggestions: ToPage.Suggestions? = null) : PageState {

        /** Подсказки к тому, что сейчас в строке; ответ на старый запрос сюда не годится. */
        fun suggestionsFor(input: String): List<WebSuggestion> =
            suggestions?.takeIf { it.query == input.trim() }?.items.orEmpty()

        /** Поиск по этой строке ответил и не нашёл ничего. */
        fun nothingFor(input: String): Boolean =
            input.isNotBlank() && suggestions?.query == input.trim() && suggestions.items.isEmpty()
    }
}

/** Реплика на странице — одна на состояние, голосом того, кому это подходит. */
data class PageLine(val mascot: Mascot, val text: String)

/** Состояние после слова телефона. */
fun PageState.after(message: ToPage): PageState = when (message) {
    is ToPage.Pairing -> PageState.Pairing(message.code)
    ToPage.Denied -> PageState.Denied
    ToPage.Busy -> PageState.Busy
    is ToPage.Day -> PageState.Diary(message.day, (this as? PageState.Diary)?.suggestions)
    is ToPage.Suggestions -> (this as? PageState.Diary)?.copy(suggestions = message) ?: this
}

/**
 * Что сказать человеку. Пускает Яйцо — оно и спрашивает на телефоне; пропавшую
 * связь объясняет Масло: оно утешает, когда что-то не так.
 */
fun PageState.line(): PageLine? = when (this) {
    PageState.Connecting -> null
    is PageState.Pairing -> PageLine(Mascot.EGG, "Код $code — такой же сейчас на телефоне. Нажми там «Пустить».")
    PageState.Denied -> PageLine(Mascot.EGG, "На телефоне сказали «нет». Попросить ещё раз?")
    PageState.Busy -> PageLine(Mascot.EGG, "В дневник просятся слишком многие. Ответь им на телефоне — и попробуем снова.")
    PageState.Lost -> PageLine(Mascot.BUTTER, "Телефон не отвечает. Открой на нём дневник — подключусь сам.")
    PageState.Left -> PageLine(Mascot.EGG, "Вышли. Чтобы вернуться, обнови страницу — и пусти браузер на телефоне снова.")
    is PageState.Diary -> if (day.entries.isEmpty()) PageLine(Mascot.EGG, "Пока пусто. Напиши, что было, — найду.") else null
}

/** Соседи со своими страницами — их показывает Зёрнышко: рядом кто-то свой, и это его забота. */
fun PageState.Diary.peersLine(): PageLine? {
    val names = day.peers.map { it.name }
    return when (names.size) {
        0 -> null
        1 -> PageLine(Mascot.GRAIN, "Рядом ${names.single()} — дневник по ссылке.")
        else -> PageLine(Mascot.GRAIN, "Рядом свои: ${names.joinToString(", ")}. Дневник каждого — по ссылке.")
    }
}

/** Записи по приёмам в порядке дня; пустые приёмы не показываются. */
fun WebDay.byMeal(): List<Pair<MealType, List<WebEntry>>> =
    MEAL_ORDER.mapNotNull { meal -> entries.filter { it.meal == meal }.takeIf { it.isNotEmpty() }?.let { meal to it } }

private val MEAL_ORDER = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.SNACK, MealType.DINNER)
