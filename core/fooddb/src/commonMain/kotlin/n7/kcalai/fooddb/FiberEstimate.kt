package n7.kcalai.fooddb

import kotlin.math.abs
import kotlin.math.max
import n7.kcalai.model.Nutriments

/**
 * Клетчатка товара, у которого её нет: берётся у того же продукта справочника.
 *
 * Строку «пищевые волокна» на пачках печатают редко, и четыре товара из пяти
 * приходят без неё. Но чернослив с пачки — тот же чернослив, что в справочнике.
 * «Тот же» проверяется дважды: название товара начинается с алиаса продукта,
 * и калории с БЖУ сходятся. Творог с черносливом вторую проверку не проходит
 * и остаётся без клетчатки — неизвестное честнее выдуманного.
 *
 * Нулю источника верим, пока товар не назван ровно как продукт, в котором
 * клетчатки заметно: «Чернослив» с нулём — ошибка того, кто вбивал. Одного
 * начала названия тут мало: «Апельсин» в начале «Апельсин сок» по цифрам от
 * апельсина не отличить, а клетчатки в соке и правда нет.
 *
 * @param lookup продукты справочника, чей алиас совпал с одной из фраз целиком; ключ — фраза
 */
internal fun estimateFiber(
    name: String,
    nutriments: Nutriments,
    lookup: (phrases: List<String>) -> Map<String, GenericRow>,
): Nutriments {
    val stated = nutriments.fiber100
    if (stated != null && stated != 0) return nutriments

    val words = tokenize(name)
    val whole = listOf(normalizeForSearch(name), words.joinToString(" "))
    val starts = (minOf(words.size, MAX_ALIAS_WORDS) downTo 1).map { words.take(it).joinToString(" ") }
    // Целиком, потом начало от длинного к короткому: «батон нарезной» точнее, чем «батон».
    val phrases = (if (stated == null) whole + starts else whole).filter(String::isNotEmpty).distinct()
    if (phrases.isEmpty()) return nutriments

    val lookalikes = lookup(phrases)
    val same = phrases.firstNotNullOfOrNull { phrase ->
        lookalikes[phrase]?.nutriments?.takeIf { sameProduct(nutriments, it) }
    } ?: return nutriments

    val fiber = same.fiber100 ?: return nutriments
    return if (stated == null || fiber >= DOUBTED_ZERO_MIN_CG) nutriments.copy(fiber100 = fiber) else nutriments
}

/**
 * Калории и каждый из макросов рядом. Допуск по углеводам широк не случайно:
 * в справочнике клетчатка местами сидит внутри углеводов, на пачке — нет.
 */
private fun sameProduct(product: Nutriments, generic: Nutriments): Boolean =
    abs(product.kcal100 - generic.kcal100) <= max(KCAL_SLACK, max(product.kcal100, generic.kcal100) * KCAL_SHARE) &&
        macroNear(product.prot100, generic.prot100) &&
        macroNear(product.fat100, generic.fat100) &&
        macroNear(product.carb100, generic.carb100)

private fun macroNear(a: Int, b: Int): Boolean = abs(a - b) <= max(MACRO_SLACK_CG, max(a, b) * MACRO_SHARE)

/** Длиннее четырёх слов алиасов в справочнике почти нет. */
private const val MAX_ALIAS_WORDS = 4

/** 15 ккал или 15% — разбег между пачками одного продукта. */
private const val KCAL_SLACK = 15.0
private const val KCAL_SHARE = 0.15

/** 4 г или 20% на каждый макрос. */
private const val MACRO_SLACK_CG = 400.0
private const val MACRO_SHARE = 0.2

/** От грамма клетчатки в продукте ноль на его пачке — уже не округление. */
private const val DOUBTED_ZERO_MIN_CG = 100
