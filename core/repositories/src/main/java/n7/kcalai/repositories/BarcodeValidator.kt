package n7.kcalai.repositories

/**
 * Контрольная цифра GTIN.
 *
 * Нужна в двух местах, и оба настоящие: человек набирает код руками с упаковки
 * и путает цифры, а распознавание изредка читает смазанную полосу не так. Проверка
 * стоит десяток операций и отсекает почти всё — вероятность, что случайная ошибка
 * сойдётся с контрольной цифрой, порядка одной десятой.
 *
 * Без неё битый код уходит в сеть, ничего не находит и приводит человека в форму
 * ручного ввода — где он заведёт продукт под кодом, которого не существует.
 */
object BarcodeValidator {

    /**
     * Приводит код к тому виду, в котором его стоит искать, или возвращает `null`.
     *
     * Нормализация обязательна, потому что один и тот же товар физически бывает
     * закодирован по-разному: UPC-E на маленькой банке — сжатая запись UPC-A,
     * а UPC-A — это EAN-13 с ведущим нулём. Искать надо тринадцатизначный вид,
     * иначе один и тот же продукт заводится в базе дважды.
     *
     * Для восьми цифр формат по самим цифрам не определяется: `01234565` —
     * настоящий UPC-E, и он же проходит контрольную сумму EAN-8. Здесь выбран
     * EAN-8, потому что на еде он встречается несопоставимо чаще, а разрешить
     * неоднозначность может только тот, кто знает формат, — см. [normalizeUpcE].
     */
    fun normalize(raw: String): String? {
        val digits = raw.trim()
        if (digits.isEmpty() || !digits.all(Char::isDigit)) return null

        return when (digits.length) {
            // EAN-8 остаётся собой: расширять его не во что.
            8 -> digits.takeIf { isChecksumValid(it) } ?: expandUpcE(digits)
            12 -> if (isChecksumValid(digits)) EAN13_PAD + digits else null
            13 -> digits.takeIf { isChecksumValid(it) }
            else -> null
        }
    }

    /**
     * То же самое, но формат известен точно — так отвечает распознаватель.
     *
     * Без этого входа каждый UPC-E, чьи восемь цифр сошлись и по EAN-8, уехал бы
     * в базу под собственным сжатым номером. Товар нашёлся бы по нему один раз
     * и никогда — по коду, под которым его знает весь остальной мир.
     */
    fun normalizeUpcE(raw: String): String? {
        val digits = raw.trim()
        if (digits.length != 8 || !digits.all(Char::isDigit)) return null
        return expandUpcE(digits)
    }

    fun isValid(raw: String): Boolean = normalize(raw) != null

    /**
     * Мод-10 со взвешиванием 3 и 1 от правого края.
     *
     * Один алгоритм на EAN-8, UPC-A и EAN-13: они различаются длиной, а не схемой.
     * Вес назначается от конца, а не от начала, — иначе на кодах разной длины
     * чередование сдвигается и проверка начинает врать ровно наполовину.
     */
    private fun isChecksumValid(digits: String): Boolean {
        val body = digits.dropLast(1)
        val expected = digits.last().digitToInt()

        var sum = 0
        for ((offset, char) in body.reversed().withIndex()) {
            sum += char.digitToInt() * if (offset % 2 == 0) 3 else 1
        }
        return (10 - sum % 10) % 10 == expected
    }

    /**
     * UPC-E -> EAN-13.
     *
     * Схема сжатия не произвольная: последняя значащая цифра говорит, сколько нулей
     * выброшено из середины UPC-A и откуда. Разворачиваем обратно и проверяем
     * контрольную цифру уже на полном коде — своей у UPC-E нет, там стоит та же.
     */
    private fun expandUpcE(digits: String): String? {
        val system = digits[0]
        if (system != '0' && system != '1') return null

        val d = digits.substring(1, 7)
        val check = digits[7]
        val body = when (d[5]) {
            '0', '1', '2' -> "${d[0]}${d[1]}${d[5]}0000${d[2]}${d[3]}${d[4]}"
            '3' -> "${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}"
            '4' -> "${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}"
            else -> "${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}0000${d[5]}"
        }

        val upcA = "$system$body$check"
        return if (isChecksumValid(upcA)) EAN13_PAD + upcA else null
    }

    /** UPC-A — это EAN-13 с ведущим нулём, и в тринадцатизначном виде их пора уравнять. */
    private const val EAN13_PAD = "0"
}
