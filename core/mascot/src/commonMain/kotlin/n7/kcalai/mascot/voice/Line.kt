package n7.kcalai.mascot.voice

import n7.kcalai.mascot.Facts
import n7.kcalai.mascot.value
import n7.kcalai.model.Sex

/**
 * Один вариант — предложение или его часть, со ссылками на факты.
 *
 * `{gap}` — факт из [Facts]; `{ел|ела}` — форма по полу человека. Вариант,
 * которому не хватает факта или пола, не выбирается: «ты ел» женщине
 * разрушает иллюзию быстрее любой другой ошибки.
 *
 * [id] — отпечаток текста. По нему помнится сказанное: правка текста делает
 * вариант новым, и это правильно — сказано-то было другое.
 */
class Line(val text: String, private val fits: (Facts) -> Boolean = { true }) {

    val id: String = fnv1a(text)

    private val tokens: List<String> = TOKEN.findAll(text).map { it.groupValues[1] }.toList()

    /** Факты, которые нужны варианту. */
    internal val keys: Set<String> = tokens.filterNot { '|' in it }.toSet()

    /** Есть ли в варианте форма по полу. */
    internal val gendered: Boolean = tokens.any { '|' in it }

    fun renders(facts: Facts): Boolean =
        (!gendered || facts.sex != null) && keys.all { facts.value(it) != null } && fits(facts)

    /** Текст с подставленными фактами. Каждая часть фразы — с заглавной: это начало предложения. */
    fun render(facts: Facts): String =
        TOKEN.replace(text) { match ->
            val body = match.groupValues[1]
            if ('|' in body) {
                val (male, female) = body.split('|', limit = 2)
                if (facts.sex == Sex.FEMALE) female else male
            } else {
                checkNotNull(facts.value(body)) { "нет факта {$body} для «$text»" }
            }
        }.replaceFirstChar(Char::uppercaseChar)

    override fun toString(): String = text
}

internal val TOKEN = Regex("""\{([^{}]+)}""")

/** FNV-1a, 32 бита, восемь hex-знаков: стабилен между запусками и платформами, в отличие от hashCode. */
private fun fnv1a(text: String): String {
    var hash = 0x811c9dc5.toInt()
    for (char in text) {
        hash = hash xor char.code
        hash *= 0x01000193
    }
    return hash.toUInt().toString(16).padStart(8, '0')
}
