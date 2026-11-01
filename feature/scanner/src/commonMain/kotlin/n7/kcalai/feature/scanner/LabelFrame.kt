package n7.kcalai.feature.scanner

import n7.kcalai.repositories.LabelReading

/**
 * Один разобранный кадр.
 *
 * @param elapsedMs сколько занял кадр целиком — распознавание плюс разбор.
 *        Не украшение: инференс идёт десятки миллисекунд, и когда съёмка
 *        начинает тормозить, узнать об этом надо не из ощущений.
 * @param available OCR поднялся на этом устройстве. `false` — не сбой, а режим:
 *        нативной библиотеки под этот ABI может не быть вовсе.
 */
internal data class LabelFrame(
    /** Уже согласованное несколькими кадрами — см. [LabelConsensus]. */
    val reading: LabelReading,
    val elapsedMs: Long,
    val available: Boolean = true,
    /** Размер кадра, ушедшего в распознавание, — уже после поворота. */
    val size: String = "",
    /** Насколько набрано согласие по каждому из четырёх значений. */
    val fields: List<Pair<String, LabelConsensus.Field>> = emptyList(),
    val frames: Int = 0,
    /** Что на пачке может быть названием, по убыванию числа подтвердивших кадров. */
    val names: List<String> = emptyList(),
    /** Название, за которое проголосовали кадры. `null` — ещё не набралось. */
    val name: String? = null,
    /** `false` — кадр мыльный и пропущен; остальное в нём — от прошлого резкого. */
    val sharp: Boolean = true,
)
