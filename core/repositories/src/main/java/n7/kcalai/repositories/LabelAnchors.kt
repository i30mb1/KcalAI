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

    /**
     * Что ищем, какому полю это соответствует и как называется в диагностике.
     *
     * Короткие подписи в [title] — для тестового режима съёмки: он показывает,
     * что прочиталось, прямо на экране, и «PROT» там читалось бы хуже, чем «Б».
     */
    internal enum class Anchor(val title: String) {
        KCAL("ккал"),
        KJ("кДж"),
        PROT("Б"),
        FAT("Ж"),
        CARB("У"),
    }

    /**
     * Латиница, которую распознаватель подставляет вместо кириллицы того же начертания.
     *
     * Объявлена первой намеренно: [normalize] читает её, а через неё проходят
     * и сами искомые слова — инициализация идёт по порядку объявления, и таблица
     * обязана существовать раньше всех, кто ею пользуется.
     */
    private val HOMOGLYPHS: Map<Char, Char> = mapOf(
        'a' to 'а', 'b' to 'ь', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'k' to 'к',
        'm' to 'м', 'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у',
        // «г» в рубленых шрифтах этикеток — это палка с засечкой влево, и модель
        // отдаёт её латинской «r» сплошь: «0r», «99,9r», «на 100r». Пока этой
        // пары тут не было, заголовок колонки «На 100 г» не узнавался вовсе,
        // а вместе с ним не узнавалась и сама колонка.
        'r' to 'г',
        'ё' to 'е',
    )

    /**
     * Написания, по которым узнаём подпись, — уже в нормализованном виде.
     *
     * Прогон через [normalize] здесь не формальность. Нормализация схлопывает
     * латиницу в кириллицу, и слово «kcal», записанное латиницей, после неё
     * не совпадает само с собой: текст на снимке превращается в «ксаl», а эталон
     * остался «kcal». Пока эталоны не проходили ту же обработку, все латинские
     * написания в этой таблице были мёртвым грузом и не совпадали никогда.
     *
     * Варианты не для красоты: «углеводы» и «углеводов» встречаются оба,
     * а нечёткое сравнение с одним написанием на длинных словах начинает
     * путать падежи с опечатками.
     */
    private val WORDS: Map<Anchor, List<String>> = listOf(
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
    ).groupBy({ it.first }, { normalize(it.second) })

    /** Единица энергии -> что она обозначает. Ключи нормализованы — см. [WORDS]. */
    private val ENERGY_UNITS: Map<String, Anchor> = mapOf(
        "ккал" to Anchor.KCAL,
        "kcal" to Anchor.KCAL,
        "кдж" to Anchor.KJ,
        "kj" to Anchor.KJ,
    ).mapKeys { normalize(it.key) }

    /**
     * Число вместе с единицей энергии, стоящей сразу за ним.
     *
     * Собирается из [ENERGY_UNITS], а не пишется руками: обе стороны обязаны
     * знать одни и те же написания, а разъехавшись — дадут единицу, которую
     * регулярное выражение нашло, а разбор не опознал.
     */
    private val ENERGY_INLINE = Regex(
        """(\d+(?:[.,]\d+)?)\s*(${ENERGY_UNITS.keys.joinToString("|") { Regex.escape(it) }})"""
    )

    /**
     * Сколько букв позволено переврать.
     *
     * Доля от длины слова, а не константа: одна ошибка в «жиры» — это четверть
     * слова, одна в «углеводов» — шум распознавания.
     */
    private const val MAX_ERROR_RATIO = 0.5

    /**
     * Больше двух ошибок не прощается никому.
     *
     * Без потолка доля превращает длинные слова в решето: у «углеводов» половина —
     * это четыре буквы, а слово, от которого осталось пять, опознавать нельзя.
     */
    private const val MAX_ERRORS = 2

    /**
     * Слова короче этого не проверяются нечётко вовсе.
     *
     * На трёх буквах допуск в одну ошибку делает похожим почти всё.
     */
    private const val MIN_FUZZY_LENGTH = 4

    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")

    /**
     * Что удалось прочитать по подписям.
     *
     * Отсутствие значения и отсутствие подписи — разные вещи, и различать их
     * обязательно. У сахара, мармелада или газировки на этикетке напечатаны
     * только калории и углеводы: белков и жиров там нет вовсе, а не «не удалось
     * прочитать». В первом случае правильный ответ — ноль, во втором — оставить
     * поле пустым и не мешать человеку.
     *
     * @param seen подписи, которые вообще встретились на снимке
     */
    internal class Anchored(
        private val values: Map<Anchor, Int>,
        private val seen: Set<Anchor>,
        /** Калории так, как они напечатаны килокалориями. */
        private val kcalDeclared: Int?,
        /** Те же калории, пересчитанные из килоджоулей. */
        private val kcalFromKj: Int?,
    ) {

        val kcal100: Int? get() = kcalDeclared ?: kcalFromKj

        /**
         * Оба прочтения энергии, от напечатанного к пересчитанному.
         *
         * По ТР ТС 022/2011 энергию печатают дважды, и это бесплатная проверка
         * распознавания: разойдясь больше, чем на округление, две величины
         * означают, что одну из них прочитали неверно. Какую именно — решать
         * не здесь: у подписей на это данных нет, а у макросов есть.
         */
        val kcalCandidates: List<Int> get() = listOfNotNull(kcalDeclared, kcalFromKj).distinct()

        /**
         * Обе величины энергии прочитаны и сходятся по константе 4.184.
         *
         * Это проверка калорий, не зависящая от макросов, и потому единственная,
         * под которую можно досчитывать недостающий макрос: считать его от числа,
         * в котором сам не уверен, значит размножать ошибку, а не устранять
         * пробел. Допуск шире отношения килоджоулей не по небрежности —
         * производитель округляет обе величины по отдельности, и «80 ккал»
         * с «340 кДж» на одной пачке расходятся на процент с лишним законно.
         */
        val kcalCorroborated: Boolean
            get() {
                val declared = kcalDeclared ?: return false
                val fromKj = kcalFromKj ?: return false
                return abs(declared - fromKj) <= declared * ENERGY_AGREEMENT
            }


        /**
         * Таблица пищевой ценности опознана: есть калории и хотя бы одна подпись макроса.
         *
         * Пока это не так, судить об отсутствующих подписях нельзя — может быть,
         * мы просто смотрим не на ту сторону упаковки.
         */
        val tableFound: Boolean
            get() = kcal100 != null && MACROS.any { it in seen }

        val prot100: Int? get() = resolve(Anchor.PROT)
        val fat100: Int? get() = resolve(Anchor.FAT)
        val carb100: Int? get() = resolve(Anchor.CARB)

        val complete: Boolean
            get() = kcal100 != null && prot100 != null && fat100 != null && carb100 != null

        /** Подписи, прочитанные вместе со своим числом. */
        val readLabels: List<String>
            get() = buildList {
                if (kcal100 != null) add(Anchor.KCAL.title)
                values.keys.sortedBy { it.ordinal }.forEach { add(it.title) }
            }

        /** Подписи, которых на этикетке нет, — их значения приняты за ноль. */
        val zeroedLabels: List<String>
            get() = if (!tableFound) emptyList()
            else MACROS.filter { it !in seen }.map { it.title }

        private fun resolve(anchor: Anchor): Int? = when {
            values[anchor] != null -> values[anchor]
            // Подписи нет на этикетке, а таблица прочитана — значит, макроса
            // в продукте нет. Ноль здесь не догадка, а то, что написано.
            tableFound && anchor !in seen -> 0
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
        val rows = lines.mapIndexed { index, line ->
            val normalized = normalize(line.text)
            Row(
                index = index,
                line = line,
                text = maskPer100(normalized),
                headsPer100 = PER_100.containsMatchIn(normalized),
            )
        }
        val column = columnX(rows)
        val values = mutableMapOf<Anchor, Double>()

        /**
         * Числа, уже отданные какой-то подписи.
         *
         * Одно число — одна подпись, и это не оптимизация. В таблице энергии
         * подписи «ккал» и «кДж» стоят одна под другой, а значения — одно
         * под другим; стоит распознавателю потерять нижнее, и обе подписи
         * дотянутся до одного и того же числа. Дальше хуже всего: две величины,
         * которые должны проверять друг друга, оказываются одной, и проверка
         * из настоящей превращается в тавтологию.
         */
        val taken = mutableSetOf<Pair<Int, Int>>()

        // Энергия почти всегда напечатана вместе со своей единицей, причём обе
        // величины в одной строке: «506 кДж / 121 ккал». Брать первое число
        // такой строки нельзя — оно окажется килоджоулями. Поэтому число
        // привязывается к единице, стоящей сразу за ним.
        val energy = buildList {
            for (row in rows) {
                ENERGY_INLINE.findAll(row.text).forEach { match ->
                    val value = match.groupValues[1].replace(',', '.').toDoubleOrNull()
                        ?: return@forEach
                    val anchor = ENERGY_UNITS[match.groupValues[2]] ?: return@forEach
                    add(Energy(anchor, value, row, match.range.first))
                }
            }
        }

        // Энергия печатается по разу на колонку, и на масле их две: «90 ккал»
        // в столовой ложке и «899 ккал» в ста граммах. Брать первую попавшуюся
        // значит ошибиться ровно в десять раз — поэтому выбирает колонка.
        for (anchor in listOf(Anchor.KCAL, Anchor.KJ)) {
            val best = energy.filter { it.anchor == anchor }.nearest(column) { it.row.line } ?: continue
            values[anchor] = best.value
            taken += best.row.index to best.at
        }

        // Подписи собираются отдельно от значений: подпись без читаемого числа —
        // это «не прочитали», а её отсутствие — «в продукте этого нет».
        val seen = mutableSetOf<Anchor>()
        seen += values.keys

        for (row in rows) {
            val hits = anchorsIn(row.text)
            for (hit in hits) {
                seen += hit.anchor
                if (hit.anchor in values) continue
                val found = valueFor(hit, row, rows, taken, column, hits.size == 1) ?: continue
                // Первое найденное выигрывает: строки идут сверху вниз, а таблица
                // читается в том же порядке.
                values[hit.anchor] = found.value
                taken += found.row to found.at
            }
        }

        val macros = buildMap {
            values[Anchor.PROT].toCentigrams()?.let { put(Anchor.PROT, it) }
            values[Anchor.FAT].toCentigrams()?.let { put(Anchor.FAT, it) }
            values[Anchor.CARB].toCentigrams()?.let { put(Anchor.CARB, it) }
        }

        return Anchored(
            values = macros,
            seen = seen,
            kcalDeclared = values[Anchor.KCAL].toKcal(),
            // Калории могли быть напечатаны только в килоджоулях.
            kcalFromKj = values[Anchor.KJ]?.div(KJ_PER_KCAL).toKcal(),
        )
    }

    private fun Double?.toKcal(): Int? =
        this?.takeIf { it in KCAL_RANGE }?.let { Math.round(it).toInt() }

    /**
     * Строка снимка вместе с её приведённым к разбору текстом.
     *
     * @param headsPer100 в строке стоит «на 100 г» — она может оказаться
     *        заголовком нужной колонки
     */
    private class Row(
        val index: Int,
        val line: TextLine,
        val text: String,
        val headsPer100: Boolean,
    )

    /** Найденное число и то, где именно оно стояло: по этому адресу его и занимают. */
    private class Found(val value: Double, val row: Int, val at: Int)

    /** Число с единицей энергии и место, где оно стоит. */
    private class Energy(val anchor: Anchor, val value: Double, val row: Row, val at: Int)

    /**
     * Где стоит колонка «на 100 г».
     *
     * Колонок на этикетке бывает несколько — «в столовой ложке», «на порцию»,
     * «на 100 г», — и до сих пор разбор брал ближайшее к подписи число, то есть
     * молча считал, что нужная колонка первая. На подсолнечном масле она вторая,
     * и вся таблица читалась в пересчёте на столовую ложку: девяносто килокалорий
     * вместо восьмисот девяноста девяти. Хуже всего, что такой набор сходится
     * по Этуотеру не хуже верного — ошибка в десять раз одинаково делится
     * на все четыре числа, и поймать её проверкой нельзя.
     *
     * Поэтому колонка не угадывается по порядку, а читается: у неё есть
     * заголовок, и он прямо говорит, что внизу сто грамм.
     *
     * Из нескольких подходящих строк берётся самая узкая. «На 100 г» в ячейке
     * заголовка — это короткая надпись над своей колонкой, а «Пищевая ценность
     * 100 г продукта (средние значения)» — фраза во всю ширину этикетки,
     * и её середина не указывает никуда.
     */
    private fun columnX(rows: List<Row>): Float? = rows
        .filter { it.headsPer100 }
        .minByOrNull { it.line.width }
        ?.line?.centerX

    /**
     * Ближайшее к нужной колонке, а без заголовка — самое левое.
     *
     * Левое как запасной порядок остаётся не случайно: когда колонка одна,
     * левее подписи стоит она же, а когда колонок несколько и заголовок
     * не прочитался, «на 100 г» на российских этикетках всё-таки чаще первая.
     */
    private fun <T> List<T>.nearest(column: Float?, line: (T) -> TextLine): T? = when (column) {
        null -> minByOrNull { line(it).left }
        else -> minByOrNull { abs(line(it).centerX - column) }
    }

    /** Найденная подпись и то, где она кончилась: значение стоит правее неё. */
    private class Hit(val anchor: Anchor, val after: Int)

    /**
     * Все подписи, которые есть в строке, а не первая попавшаяся.
     *
     * Разница принципиальная, и стоила она целого класса этикеток. Сахар, мёд,
     * газировка печатают таблицу одной строкой: «Углеводы — 99,8 г,
     * энергетическая ценность — 399 ккал». Разбор, умеющий видеть в строке одну
     * подпись, находил здесь «ккал», на углеводы уже не смотрел — и таблица
     * не опознавалась вовсе, хотя прочитана была целиком.
     */
    private fun anchorsIn(text: String): List<Hit> {
        if (text.isEmpty()) return emptyList()

        val tokens = tokenize(text)
        return Anchor.entries.mapNotNull { anchor ->
            val words = WORDS[anchor].orEmpty()
            val end = exactEnd(text, words) ?: fuzzyEnd(tokens, words)
            end?.let { Hit(anchor, it) }
        }
    }

    /** Самое левое точное вхождение: подпись стоит перед своим числом. */
    private fun exactEnd(text: String, words: List<String>): Int? = words
        .mapNotNull { word -> text.indexOf(word).takeIf { it >= 0 }?.plus(word.length) }
        .minOrNull()

    /**
     * Порог длины проверяется у эталона, а не у прочитанного слова.
     *
     * Разница не формальная. Распознаватель теряет буквы целиком: «Жиры, г»
     * приходит как «*уры,г», и от слова остаётся три буквы. Требуя четырёх
     * от прочитанного, разбор объявлял бы, что подписи «жиры» на этикетке нет
     * вовсе, — а это по здешним правилам значит «жиров в продукте нет»
     * и молча ставит ноль там, где стоит шестьдесят семь грамм.
     *
     * Отдельная проверка длины тут и не нужна: [levenshtein] сам отсекает всё,
     * что отличается длиной больше допуска.
     */
    private fun fuzzyEnd(tokens: List<Token>, words: List<String>): Int? {
        for (token in tokens) {
            for (word in words) {
                if (word.length < MIN_FUZZY_LENGTH) continue
                // Выпасть может самое большее одна буква. Иначе двух ошибок хватает,
                // чтобы «ры» сошлось с «жиры»: обе оставшиеся буквы совпали,
                // а слова там уже нет.
                if (token.text.length < word.length - 1) continue

                val allowed = (word.length * MAX_ERROR_RATIO).toInt()
                    .coerceIn(1, MAX_ERRORS)
                if (levenshtein(token.text, word, allowed) <= allowed) return token.end
            }
        }
        return null
    }

    /**
     * Число для подписи.
     *
     * Сначала — правее самой подписи в её же строке: распознаватель часто отдаёт
     * «Белки 17,2» одним куском. Если там его нет, ищем ближайшую строку справа
     * на той же высоте.
     *
     * @param soleAnchor подпись в строке одна. Тогда — и только тогда — годится
     *        любое число строки, даже стоящее перед подписью: вёрстка с числом
     *        слева редка, но бывает, и терять на ней значение незачем. Когда
     *        подписей несколько, такая поблажка отдала бы углеводам число жиров.
     */
    private fun valueFor(
        hit: Hit,
        row: Row,
        rows: List<Row>,
        taken: Set<Pair<Int, Int>>,
        column: Float?,
        soleAnchor: Boolean,
    ): Found? {
        numberAfter(row, hit.after, taken)?.let { return it }
        if (soleAnchor) numberAfter(row, 0, taken)?.let { return it }

        // Строки той же высоты, что подпись, — это её строка таблицы. Какая
        // из них нужная колонка, решает заголовок, а не порядок слева направо.
        //
        // Негодные числа отсеиваются до выбора, а не после, и это не перестановка
        // строк. Строка таблицы задевает по высоте соседнюю — на масле в строку
        // белков попадает «376 кДж» из энергетической ячейки, — и, отбирая
        // ближайшее к колонке, а уже потом проверяя, что в сто грамм не влезет
        // триста семьдесят шесть, разбор выбрасывал вместе с ним и настоящий
        // ноль, стоявший рядом.
        val candidates = rows
            .filter { it !== row && it.line.left >= row.line.left && row.line.sameRow(it.line, ROW_REACH) }
            .mapNotNull { candidate -> numberAfter(candidate, 0, taken)?.let { candidate to it } }
            .filter { (_, found) ->
                hit.anchor == Anchor.KCAL || hit.anchor == Anchor.KJ || found.value <= MACRO_MAX
            }

        // Сначала ряд, потом колонка — двумерная таблица разбирается по одной оси
        // за раз. Ряд берётся ближайший по высоте, а не любой попавший в допуск:
        // на масле значения стоят на пятнадцать точек ниже своей подписи, при том
        // что соседний ряд лежит в одиннадцати, — выбирая «любой подходящий»,
        // разбор брал бы чужую строку, а взяв ближайший, берёт свою.
        val closest = candidates.minByOrNull { abs(it.first.line.centerY - row.line.centerY) }
            ?: return null
        val band = maxOf(row.line.height, closest.first.line.height) * ROW_BAND

        return candidates
            .filter { abs(it.first.line.centerY - closest.first.line.centerY) <= band }
            .nearest(column) { it.first.line }
            ?.second
    }

    /**
     * Насколько далеко от подписи ищется её значение.
     *
     * Шире умолчания [TextLine.sameRow] намеренно. Ячейка таблицы и подпись
     * выравниваются по-разному — по центру, по верху, по базовой линии, —
     * и на объединённых ячейках расхождение доходит до высоты строки. Широкий
     * захват здесь не опасен ровно потому, что из захваченного выбирается
     * ближайшее, а не первое подвернувшееся.
     */
    private const val ROW_REACH = 0.9f

    /** Насколько два числа должны совпасть по высоте, чтобы считаться одним рядом. */
    private const val ROW_BAND = 0.5f

    /** Первое свободное число, начинающееся не раньше [from]. «на 100 г» сюда уже не попадает. */
    private fun numberAfter(row: Row, from: Int, taken: Set<Pair<Int, Int>>): Found? =
        NUMBER.findAll(row.text)
            .filter { it.range.first >= from }
            .filterNot { leadingZero(it.value) }
            .filterNot { (row.index to it.range.first) in taken }
            .firstOrNull()
            ?.let { match ->
                match.value.replace(',', '.').toDoubleOrNull()
                    ?.let { Found(it, row.index, match.range.first) }
            }

    /**
     * «00» и подобное — не значение, а объеденная распознавателем единица.
     *
     * Этикетки печатают «0», а не «00»: ноль с ведущим нулём на упаковке
     * не встречается. Зато встречается «Углеводы, г/100 мл», у которого
     * распознаватель теряет единицу и отдаёт «г/00 мл», — и разбор, приняв
     * «00» за углеводы, ставил ноль вместо шести грамм, да ещё и уверенно.
     * Дробное «0,4» под правило не подпадает: там за нулём идёт разделитель.
     */
    private fun leadingZero(value: String): Boolean =
        value.length > 1 && value[0] == '0' && value[1].isDigit()

    /**
     * Вымарывает «на 100 г» пробелами.
     *
     * Пробелами, а не вырезанием, — не мелочь: позиции подписей и чисел в строке
     * значимы, и сдвинув их, разбор начал бы приписывать подписи чужие числа.
     */
    private fun maskPer100(normalized: String): String =
        PER_100.replace(normalized) { match -> " ".repeat(match.value.length) }

    /**
     * «на 100 г» — часть подписи, а не значение.
     *
     * Без этого «Белки на 100 г 17,2» прочитается как сто грамм белка.
     * Написания единиц — нормализованные: латинское «ml» после нормализации
     * это «мl», а не «ml».
     */
    private val PER_100 = Regex("""(?:на\s*)?100\s*(?:г|g|м[лl])""")

    /**
     * Приведение к сравнимому виду.
     *
     * «ё» к «е» — распознаватель точки над ней теряет. Латинские двойники
     * кириллических букв тоже схлопываются: на этикетке они не встречаются,
     * а распознаватель их подставляет, потому что начертание одно.
     *
     * Разделитель дробной части между двумя цифрами остаётся на месте, хотя
     * буквой не является: «17,2» обязано дожить до разбора одним числом,
     * иначе белок превратится в семнадцать грамм и потерянную двойку.
     */
    private fun normalize(raw: String): String {
        val lower = raw.lowercase()
        val builder = StringBuilder(lower.length)
        for (index in lower.indices) {
            val mapped = HOMOGLYPHS[lower[index]] ?: lower[index]
            when {
                mapped.isLetterOrDigit() -> builder.append(mapped)

                (mapped == '.' || mapped == ',') &&
                    lower.getOrNull(index - 1)?.isDigit() == true &&
                    lower.getOrNull(index + 1)?.isDigit() == true -> builder.append(mapped)

                builder.isNotEmpty() && builder.last() != ' ' -> builder.append(' ')
            }
        }
        return builder.toString().trim()
    }

    /** Слово вместе с тем, где оно кончилось. Позиция нужна, чтобы взять число правее. */
    private class Token(val text: String, val end: Int)

    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var start = 0
        for (index in text.indices) {
            if (text[index] != ' ') continue
            if (index > start) tokens += Token(text.substring(start, index), index)
            start = index + 1
        }
        if (text.length > start) tokens += Token(text.substring(start), text.length)
        return tokens
    }

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

    /** Насколько килокалории и килоджоули одной пачки вправе разойтись после округления. */
    private const val ENERGY_AGREEMENT = 0.03
    private val KCAL_RANGE = 1.0..NutrimentValidator.KCAL_MAX.toDouble()
}