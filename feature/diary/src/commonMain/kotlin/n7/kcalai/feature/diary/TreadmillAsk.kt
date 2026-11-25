package n7.kcalai.feature.diary

/**
 * Человек просит подключить беговую дорожку: «дорожка», «беговая дорожка», «подключи дорожку».
 * Это слово Зёрнышко называет, когда дорожку не подключили, — по нему выбор дорожки откроется снова.
 *
 * Строка целиком, а не слово в ней: «дорожка из моркови» — уже что-то другое.
 */
fun asksForTreadmill(text: String): Boolean {
    val words = text.lowercase().replace(PUNCTUATION, " ").split(' ').filter(String::isNotBlank)
    return words.isNotEmpty() && words.size <= 2 && words.last() in TREADMILL_WORDS && words.dropLast(1).all { it in LEADS }
}

private val PUNCTUATION = Regex("""[?!.,:;]""")

private val TREADMILL_WORDS = setOf("дорожка", "дорожку", "дорожки", "дорожке")

private val LEADS = setOf("беговая", "беговую", "подключи", "подключить")
