package n7.kcalai.webapi

import n7.kcalai.model.FiberTotal
import n7.kcalai.model.Mascot
import n7.kcalai.model.fiberSpeech

/** Плитка в шапке страницы: три макроса и клетчатка. Клетчатка — росток Зёрнышка, его тема. */
enum class WebTile(val mascot: Mascot) {
    PROTEIN(Mascot.EGG),
    FAT(Mascot.BUTTER),
    CARB(Mascot.GRAIN),
    FIBER(Mascot.GRAIN),
}

/** Сколько у хозяина плитки фраз на тап — по кругу. */
const val TILE_LINES = 3

/**
 * Что хозяин плитки говорит на тап: сначала кто он и сколько его макроса за день,
 * дальше — чем добрать и что-нибудь своё. Тапают снова — следующая фраза, по кругу:
 * одна и та же на каждый тап надоедает с третьего раза.
 *
 * Клетчатка первой фразой говорит то же, что по тапу в шапке приложения, — см. [fiberSpeech].
 *
 * @param tap который это тап по плитке подряд, с нуля
 * @param past открыт прошлый день: «сегодня» было бы неправдой
 */
fun tileSpeech(tile: WebTile, eatenCg: Int, goalGrams: Int?, fiber: FiberTotal, tap: Int, past: Boolean = false): PageLine {
    val amount = eatenCg / CG_PER_G
    val when_ = if (past) "В тот день" else "Сегодня"
    val today = "$when_ " + if (goalGrams != null) "$amount из $goalGrams г" else "$amount г"
    val lines = when (tile) {
        WebTile.PROTEIN -> listOf(
            "Я Яйцо, слежу за белком. $today.",
            "Белок держит сытость и мышцы. Добрать помогут творог, яйца и рыба.",
            "Аккуратнее, тут скорлупа!",
        )
        WebTile.FAT -> listOf(
            "Я Масло, отвечаю за жиры. $today.",
            "Жиры нужны, но понемножку: орехи, рыба, ложка масла в салат.",
            "Тихонько, я таю…",
        )
        WebTile.CARB -> listOf(
            "Я Зёрнышко, углеводы — мои! $today.",
            "Углеводы — это энергия: крупы, хлеб, фрукты. Погнали!",
            "Хохолок торчит — значит, всё по плану!",
        )
        WebTile.FIBER -> listOf(
            fiberSpeech(fiber, goalGrams ?: 0, past),
            "Я клетчатка — росток Зёрнышка. Держу сытость и кормлю полезные бактерии.",
            "Овощи, ягоды, цельные крупы — и я расту!",
        )
    }
    return PageLine(tile.mascot, lines[tap.mod(TILE_LINES)])
}

private const val CG_PER_G = 100
