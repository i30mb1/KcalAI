package n7.kcalai.feature.diary

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import n7.kcalai.model.formatHour
import n7.kcalai.model.MealType
import n7.kcalai.model.title
import n7.kcalai.personal.DayOutline


/** «82,4 кг» — вес тела. Десятые обязательны: без них взвешивание бессмысленно. */
fun formatKg(weightGrams: Int): String {
    val whole = weightGrams / 1000
    val tenths = (weightGrams % 1000) / 100
    return "$whole,$tenths кг"
}

/**
 * Приветствие по часу.
 *
 * Единственное место, где приложение обращается к человеку первым, — поэтому
 * и единственное, где уместна вежливость. Дальше оно говорит только по делу.
 */
fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Доброе утро!"
    in 12..17 -> "Добрый день!"
    else -> "Добрый вечер!"
}

/** «на ужин» — какой приём впереди. По часу, а не по тому, что уже записано. */
fun nextMealTitle(hour: Int): String = when {
    hour < 11 -> "на завтрак"
    hour < 16 -> "на обед"
    else -> "на ужин"
}

/** «на ужин» — «обычно на ужин»: подсказки для приёма прошлого дня. */
fun mealNameTarget(meal: MealType): String = "на ${mealNameLower(meal)}"

/** «обед» — название приёма в середине фразы. */
fun mealNameLower(meal: MealType): String = meal.title.lowercase()

/** «обедаешь» — «обычно ты обедаешь около 13:00». */
fun mealVerb(meal: MealType): String = when (meal) {
    MealType.BREAKFAST -> "завтракаешь"
    MealType.LUNCH -> "обедаешь"
    MealType.DINNER -> "ужинаешь"
    MealType.SNACK -> "перекусываешь"
}

/**
 * «Обычно завтрак у тебя около 08:00 и это ~450 ккал, обед ~850, ужин ~600».
 *
 * Время названо один раз, у первого приёма. Повторять «обед около 13:00 и это
 * ~850 ккал, ужин около 19:00 и это ~600 ккал» значит превратить фразу в таблицу,
 * которую никто не дочитает.
 */
fun outlineSentence(outline: DayOutline): String {
    val first = outline.meals.firstOrNull() ?: return ""
    val head = "Обычно ${mealNameLower(first.meal)} у тебя около ${formatHour(first.hour)} " +
        "и это ~${first.kcal} ккал"
    val rest = outline.meals.drop(1).joinToString("") { ", ${mealNameLower(it.meal)} ~${it.kcal}" }
    return head + rest
}

/**
 * Ответ на запрос, по которому ничего не нашлось, — сразу предложение добавить.
 *
 * Не список советов: что и как вписывать, покажет форма. Род запроса не угадать —
 * «„Борщ“ не нашлась» режет глаз, — поэтому фраза о базе, а не о запросе.
 */
fun notFoundText(query: String, assist: Assist = Assist.NONE): String = when (assist) {
    Assist.FAILED -> "Нейросеть тоже не разобрала «$query». Добавим сами?"
    else -> "«$query» в базе нет. Добавим?"
}

/** Реплика, в которой почта — ссылка на письмо: адрес не переписывают руками. */
fun mailLinked(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    MAIL.findAll(text).forEach { match ->
        addLink(
            LinkAnnotation.Url("mailto:${match.value}", TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))),
            match.range.first,
            match.range.last + 1,
        )
    }
}

private val MAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")

/** «2 сессии съёмки и 1 продукт» — что лежит в очереди отправки. */
fun describeOutbox(scans: Int, products: Int): String {
    val parts = buildList {
        if (scans > 0) add("$scans ${plural(scans, "сессия", "сессии", "сессий")} съёмки")
        if (products > 0) add("$products ${plural(products, "продукт", "продукта", "продуктов")}")
    }
    return parts.joinToString(" и ")
}

/**
 * Русское счётное слово: «1 продукт», «3 продукта», «5 продуктов».
 *
 * Правило целиком: 11–14 берут форму множественного, и без этой оговорки
 * «11 продукта» появляется ровно тогда, когда его не ждут.
 */
private fun plural(count: Int, one: String, few: String, many: String): String {
    val tens = count % 100
    if (tens in 11..14) return many
    return when (count % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}
