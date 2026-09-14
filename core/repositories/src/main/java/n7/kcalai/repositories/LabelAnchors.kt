package n7.kcalai.repositories

import kotlin.math.abs
import kotlin.math.min
import n7.kcalai.model.TextLine

/**
 * Разбор таблицы пищевой ценности по подписям, а не по арифметике.
 *
 * Арифметический разбор в [LabelParser] упирается в стену, которую не обойти:
 * у белка и углеводов один коэффициент — 4 ккал на грамм, — поэтому энергия
 * их перестановку не замечает вовсе. Единственным признаком там остаётся
 * порядок печати, а он у производителей разный.
 *
 * Здесь та же задача решается прямо: читаем подпись «Белки» и берём число,
 * стоящее с ней на одной строке. Ради этого и вводился кириллический OCR.
 *
 * Распознавание ошибается в букве-другой, поэтому сравнение нечёткое,
 * по расстоянию Левенштейна. Слов всего пять, и спутать их между собой нельзя:
 * «белки» и «жиры» не похожи ни в одном написании.
 */
internal object LabelAnchors {

    /** Что ищем и какому полю это соответствует. */
    private enum class Anchor { KCAL, KJ, PROT, FAT, CARB }

    /**
     * Написания, по которым узнаём подпись.
     *
     * Варианты не для красоты: «углеводы» и «углеводов» встречаются оба,
     * а нечёткое сравнение с одним написанием на длинных словах начинает
     * путать падежи с опечатками.
     */
    private val WORDS: List<Pair<Anchor, String>> = listOf(
        Anchor.KCAL to "ккал",
        Anchor.KCAL to "kcal",
        Anchor.KJ to "кдж",
        Anchor.KJ to "kj",
        Anchor.PROT to "белки",
        Anchor.PROT to "белков",
        Anchor.PROT to "белок",
        Anchor.FAT to "жиры",
        Anchor.FAT to "жиров",
        Anchor.CARB to "углеводы",
        Anchor.CARB to "углеводов",
    )

    /**
     * Сколько букв позволено переврать.
     *
     * Доля от длины слова, а не константа: одна ошибка в «жиры» — это четверть
     * слова и повод насторожиться, одна в «углеводов» — шум распознавания.
     */
    private const val MAX_ERROR_RATIO = 0.34

    /**
     * Слова короче этого не проверяются нечётко вовсе.
     *
     * На трёх буквах допуск в одну ошибку делает похожим почти всё.
     */
    private const val MIN_FUZZY_LENGTH = 4

    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")

    /**
     * Число вместе с единицей энергии, стоящей сразу за ним.
     *
     * Работает по уже нормализованному тексту, поэтому написание одно —
     * «кдж» и «ккал» строчными, гомоглифы схлопнуты.
     */
    private val ENERGY_INLINE = Regex("""(\d+(?:[.,]\d+)?)\s*(ккал|кдж|kcal|kj)""")

    /**
     * Что удалось прочитать по подписям.
     *
     * Отсутствие значения и отсутствие подписи — разные вещи, и различать их
     * обязательно. У сахара, мармелада или газировки на этикетке напечатаны
     * только калории и углеводы: белков и жиров там нет вовсе, а не «не удалось
     * прочитать». В первом случае правильный ответ — ноль, во втором — оставить
     * поле пустым и не мешать человеку.
     *
     * @param seenLabels подписи, которые вообще встретились на снимке
     */
    internal class Anchored(
        private val values: Map<Anchor, Int>,
        private val seenLabels: Set<Anchor>,
        val kcal100: Int?,
    ) {

        /**
         * Таблица пищевой ценности опознана: есть калории и хотя бы одна подпись макроса.
         *
         * Пока это не так, судить об отсутствующих подписях нельзя — может быть,
         * мы просто смотрим не на ту сторону упаковки.
         */
        val tableFound: Boolean
            get() = kcal100 != null && MACROS.any { it in seenLabels }

        val prot100: Int? get() = resolve(Anchor.PROT)
        val fat100: Int? get() = resolve(Anchor.FAT)
        val carb100: Int? get() = resolve(Anchor.CARB)

        val complete: Boolean
            get() = kcal100 != null && prot100 != null && fat100 != null && carb100 != null

        /** Какие подписи прочитаны, а какие приняты за ноль — нужно диагностике. */
        val readLabels: Set<String> get() = values.keys.map { it.name }.toSet()
        val zeroedLabels: Set<String>
            get() = if (!tableFound) emptySet()
            else MACROS.filter { it !in seenLabels }.map { it.name }.toSet()

        private fun resolve(anchor: Anchor): Int? = when {
            values[anchor] != null -> values[anchor]
            // Подписи нет на этикетке, а таблица прочитана — значит, макроса
            // в продукте нет. Ноль здесь не догадка, а то, что написано.
            tableFound && anchor !in seenLabels -> 0
            else -> null
        }

        private companion object {
            val MACROS = listOf(Anchor.PROT, Anchor.FAT, Anchor.CARB)
        }
    }

    /**
     * Читает значения по подписям.
     *
     * Колонка «на 100 г» отделяется от «на порцию» по горизонтали: берётся
     * та, что левее. На этикетках значения на сто грамм печатают первым
     * столбцом, а подписи стоят слева от обоих — значит, нужное число это
     * ближайшее справа от подписи, а не любое на строке.
     */
    fun read(lines: List<TextLine>): Anchored {
        val values = mutableMapOf<Anchor, Double>()

        // Энергия почти всегда напечатана вместе со своей единицей, причём обе
        // величины в одной строке: «506 кДж / 121 ккал». Брать первое число
        // такой строки нельзя — оно окажется килоджоулями. Поэтому число
        // привязывается к единице, стоящей сразу за ним.
        for (line in lines) {
            val text = normalize(line.text)
            ENERGY_INLINE.findAll(text).forEach { match ->
                val value = match.groupValues[1].replace(',', '.').toDoubleOrNull()
                    ?: return@forEach
                val anchor = if (match.groupValues[2].startsWith("кк") ||
                    match.groupValues[2].startsWith("kc")
                ) {
                    Anchor.KCAL
                } else {
                    Anchor.KJ
                }
                values.putIfAbsent(anchor, value)
            }
        }

        // Подписи собираются отдельно от значений: подпись без читаемого числа —
        // это «не прочитали», а её отсутствие — «в продукте этого нет».
        val seen = mutableSetOf<Anchor>()
        seen += values.keys

        for (line in lines) {
            val anchor = anchorOf(line.text) ?: continue
            seen += anchor
            if (anchor in values) continue
            val value = valueFor(anchor, line, lines) ?: continue
            // Первое найденное выигрывает: строки идут сверху вниз, а таблица
            // читается в том же порядке.
            values.putIfAbsent(anchor, value)
        }

        // Калории могли быть напечатаны только в килоджоулях.
        val kcal = values[Anchor.KCAL]
            ?: values[Anchor.KJ]?.let { it / KJ_PER_KCAL }

        val macros = buildMap {
            values[Anchor.PROT].toCentigrams()?.let { put(Anchor.PROT, it) }
            values[Anchor.FAT].toCentigrams()?.let { put(Anchor.FAT, it) }
            values[Anchor.CARB].toCentigrams()?.let { put(Anchor.CARB, it) }
        }

        return Anchored(
            values = macros,
            seenLabels = seen,
            kcal100 = kcal?.takeIf { it in KCAL_RANGE }?.let { Math.round(it).toInt() },
        )
    }

    /**
     * Число для подписи.
     *
     * Сначала — в самой строке: распознаватель часто отдаёт «Белки 17,2» одним
     * куском. Если там его нет, ищем ближайшую строку справа на той же высоте.
     */
    private fun valueFor(anchor: Anchor, label: TextLine, lines: List<TextLine>): Double? {
        inline(label.text)?.let { return it }

        return lines.asSequence()
            .filter { it !== label && it.left >= label.left && label.sameRow(it) }
            .sortedBy { it.left }
            .mapNotNull { inline(it.text) }
            .firstOrNull()
            ?.takeIf { anchor == Anchor.KCAL || anchor == Anchor.KJ || it <= MACRO_MAX }
    }

    /** Первое число в строке. Подпись «на 100 г» своей сотни сюда не приносит. */
    private fun inline(text: String): Double? {
        val cleaned = text.replace(PER_100, " ")
        return NUMBER.find(cleaned)?.value?.replace(',', '.')?.toDoubleOrNull()
    }

    /**
     * «на 100 г» — часть подписи, а не значение.
     *
     * Без этого «Белки на 100 г 17,2» прочитается как сто грамм белка.
     */
    private val PER_100 = Regex("""(?:на\s*)?100\s*(?:г|g|мл|ml)""", RegexOption.IGNORE_CASE)

    private fun anchorOf(raw: String): Anchor? {
        val text = normalize(raw)
        if (text.isEmpty()) return null

        for ((anchor, word) in WORDS) {
            if (text.contains(word)) return anchor
        }
        // Точного вхождения нет — пробуем нечётко по отдельным словам.
        val tokens = text.split(' ').filter { it.length >= MIN_FUZZY_LENGTH }
        for ((anchor, word) in WORDS) {
            if (word.length < MIN_FUZZY_LENGTH) continue
            val allowed = (word.length * MAX_ERROR_RATIO).toInt().coerceAtLeast(1)
            if (tokens.any { levenshtein(it, word, allowed) <= allowed }) return anchor
        }
        return null
    }

    /**
     * Приведение к сравнимому виду.
     *
     * «ё» к «е» — распознаватель точки над ней теряет. Латинские двойники
     * кириллических букв тоже схлопываются: на этикетке они не встречаются,
     * а распознаватель их подставляет, потому что начертание одно.
     */
    private fun normalize(raw: String): String {
        val builder = StringBuilder(raw.length)
        for (char in raw.lowercase()) {
            val mapped = HOMOGLYPHS[char] ?: char
            when {
                mapped.isLetterOrDigit() -> builder.append(mapped)
                builder.isNotEmpty() && builder.last() != ' ' -> builder.append(' ')
            }
        }
        return builder.toString().trim()
    }

    /** Латиница, которую распознаватель подставляет вместо кириллицы того же начертания. */
    private val HOMOGLYPHS: Map<Char, Char> = mapOf(
        'a' to 'а', 'b' to 'ь', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'k' to 'к',
        'm' to 'м', 'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у',
        'ё' to 'е',
    )

    /**
     * Расстояние Левенштейна с досрочным выходом.
     *
     * [limit] не оптимизация, а часть смысла: нас интересует только «похоже
     * ли», и считать точное расстояние до заведомо чужого слова незачем.
     */
    private fun levenshtein(a: String, b: String, limit: Int): Int {
        if (abs(a.length - b.length) > limit) return limit + 1

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(
                    min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost,
                )
                rowMin = min(rowMin, current[j])
            }
            if (rowMin > limit) return limit + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun Double?.toCentigrams(): Int? =
        this?.takeIf { it in 0.0..MACRO_MAX }?.let { Math.round(it * 100).toInt() }

    private const val KJ_PER_KCAL = 4.184
    private const val MACRO_MAX = 100.0
    private val KCAL_RANGE = 1.0..NutrimentValidator.KCAL_MAX.toDouble()
}
