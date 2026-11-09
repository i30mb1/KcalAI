package n7.kcalai.feature.diary

import androidx.compose.ui.geometry.Rect
import n7.kcalai.model.Mascot
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

    /**
     * Ушёл в шит поверх экрана: там он у реплики или в поле. На главном экране его нет —
     * персонаж один, и второй копии за затемнением быть не должно.
     */
    data object Offstage : MascotPlace
}

/**
 * Места, которые сейчас видно.
 *
 * Лента идёт под стеклянными шапкой и строкой ввода, и реплика, целиком ушедшая
 * под них, из ленты не выпадает. Её место считается пропавшим, как у реплики за
 * краем ленты: иначе персонаж стоял бы поверх цифр шапки или набранного текста.
 * Выглядывающая реплика место держит. Места в самих полосах — дом в плитке шапки
 * и чипс над строкой ввода — стекло не закрывает.
 *
 * @param coverBottom нижний край шапки в координатах корня
 * @param floorTop верхний край строки ввода в координатах корня
 */
internal fun shownAnchors(rects: Map<String, Rect>, coverBottom: Float, floorTop: Float): Set<String> =
    rects.filter { (key, rect) ->
        key.startsWith(HOME_PREFIX) || key == CHIP_ANCHOR || (rect.bottom > coverBottom && rect.top < floorTop)
    }.keys

/**
 * Места всех троих.
 *
 * Чипс важнее реплики: подсказка под пальцем — то, о чём речь прямо сейчас.
 * Реплика важнее дома. Сцена — только пока человек ничего не начал: стоит
 * набрать букву, и знакомство кончилось.
 *
 * @param shownKeys ключи реплик, которые сейчас на экране. Ушедшая за край
 * реплика держать персонажа не может — он висел бы в пустоте.
 * @param offstage открыт шит с персонажами: все трое там, а не на экране
 */
fun mascotPlaces(
    feed: List<FeedItem>,
    shownKeys: Set<String>,
    chipOwner: Mascot?,
    offstage: Boolean = false,
): Map<Mascot, MascotPlace> {
    val stage = chipOwner == null && feed.any { it is FeedItem.Summary && it.intro }
    return Mascot.entries.associateWith { mascot ->
        when {
            offstage -> MascotPlace.Offstage
            stage -> MascotPlace.Stage
            mascot == chipOwner -> MascotPlace.Chip
            else -> feed
                .lastOrNull { it.key in shownKeys && speakerOf(it) == mascot }
                ?.let { MascotPlace.Reply(it.key) }
                ?: MascotPlace.Home
        }
    }
}

/**
 * Чьи домики в шапке пустуют: персонаж у реплики, на чипсе или на сцене.
 *
 * Дома персонаж сам и есть подпись плитки: яйцо узнаётся быстрее слова «белки».
 * На месте ушедшего встаёт слово, иначе плитка осталась бы двумя безымянными числами.
 */
fun emptyHomes(places: Map<Mascot, MascotPlace>): Set<Mascot> =
    places.filterValues { it != MascotPlace.Home }.keys

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
