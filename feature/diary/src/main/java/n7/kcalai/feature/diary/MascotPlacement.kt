package n7.kcalai.feature.diary

import n7.kcalai.ui.mascot.Mascot
import n7.kcalai.ui.mascot.mascot

/**
 * Где на экране стоит персонаж.
 *
 * Каждый из троих существует в одном экземпляре и переходит с места на место,
 * а не рисуется у каждой своей реплики. Иначе в длинной ленте их десяток,
 * и персонаж превращается в иконку.
 */
sealed interface MascotPlace {
    /** Своя плитка в шапке. */
    data object Home : MascotPlace

    /** Первый чипс над строкой ввода — подсказка про его макрос. */
    data object Chip : MascotPlace

    /** У последней своей реплики, которая видна на экране. */
    data class Reply(val key: String) : MascotPlace

    /** Пустой первый день: все трое крупно в ленте. Места полно, и это знакомство. */
    data object Stage : MascotPlace
}

/**
 * Места всех троих.
 *
 * Чипс важнее реплики: подсказка под пальцем — то, о чём речь прямо сейчас.
 * Реплика важнее дома. Сцена — только пока человек ничего не начал: стоит
 * набрать букву, и знакомство кончилось.
 *
 * @param shownKeys ключи реплик, которые сейчас на экране. Ушедшая за край
 * реплика держать персонажа не может — он висел бы в пустоте.
 */
fun mascotPlaces(feed: List<FeedItem>, shownKeys: Set<String>, chipOwner: Mascot?): Map<Mascot, MascotPlace> {
    val stage = chipOwner == null && feed.any { it is FeedItem.Summary && it.intro }
    return Mascot.entries.associateWith { mascot ->
        when {
            stage -> MascotPlace.Stage
            mascot == chipOwner -> MascotPlace.Chip
            else -> feed
                .lastOrNull { it.key in shownKeys && speakerOf(it) == mascot }
                ?.let { MascotPlace.Reply(it.key) }
                ?: MascotPlace.Home
        }
    }
}

/** Чей макрос у первой подсказки. У веса и пустого ряда хозяина нет. */
fun chipOwner(row: ComposerRow): Mascot? {
    val nutriments = when (row) {
        is ComposerRow.Results -> row.items.firstOrNull()?.candidate?.nutriments
        is ComposerRow.Scanned -> row.item.candidate?.nutriments
        is ComposerRow.Predictions -> row.items.firstOrNull()?.nutriments
        is ComposerRow.Weight, ComposerRow.None -> null
    }
    return nutriments?.let { dominantMacro(it).mascot }
}
