package n7.kcalai.server

/**
 * Контрольная цифра GTIN — мод-10 со взвешиванием 3 и 1 от правого края.
 *
 * Клиент уже проверил, но клиент — не то место, на которое сервер вправе
 * полагаться: в пул попадает только то, что сошлось и здесь.
 */
object Gtin {

    fun isValid(digits: String): Boolean {
        if (digits.length != 8 && digits.length != 13) return false
        if (!digits.all(Char::isDigit)) return false

        val body = digits.dropLast(1)
        var sum = 0
        for ((offset, char) in body.reversed().withIndex()) {
            sum += char.digitToInt() * if (offset % 2 == 0) 3 else 1
        }
        return (10 - sum % 10) % 10 == digits.last().digitToInt()
    }
}
