package n7.kcalai.mascot.voice

import kotlin.random.Random
import n7.kcalai.mascot.Facts

/** Сказанное: текст и варианты, из которых он сложен, — чтобы в следующий раз их обойти. */
data class Utterance(val text: String, val lineIds: List<String>)

/**
 * Фраза на тему из вариантов голоса.
 *
 * Форма фразы выбирается случайно. В каждом месте — вариант из тех, что звучали
 * давнее всего: ротация, а не жребий. Жребий по короткому набору повторяется уже
 * через несколько сообщений. Но и чистая ротация плоха: наборы разной длины
 * крутятся в такт, и сочетания возвращаются по кругу. Поэтому из давних берётся
 * случайный, а сложившаяся точная копия недавнего сообщения собирается заново —
 * уже из любых подходящих вариантов.
 *
 * Факт звучит во фразе один раз: «Женя, привет. Женя, ты сможешь!» не пишет никто.
 * Длинная фраза теряет необязательные части, а не обрезается посреди слова;
 * не влезает и обязательное — эта форма фразы не годится, пробуется следующая.
 *
 * @param recent варианты недавних сообщений — по списку на сообщение, новые первыми
 * @return `null`, если на эту тему по этим фактам сказать нечего
 */
fun Voice.compose(topic: Topic, facts: Facts, recent: List<List<String>>, random: Random): Utterance? {
    val age = HashMap<String, Int>()
    recent.flatten().forEachIndexed { index, id -> age.getOrPut(id) { index } }
    val said = recent.toHashSet()

    var first: Utterance? = null
    repeat(ATTEMPTS) { attempt ->
        val utterance = composeOnce(topic, facts, age, random, anyLine = attempt > 0) ?: return first
        if (utterance.lineIds !in said) return utterance
        if (first == null) first = utterance
    }
    // Всё уже звучало в точности так: повтор лучше молчания.
    return first
}

/** Сколько раз пробовать собрать фразу, не повторяющую недавнее сообщение целиком. */
private const val ATTEMPTS = 16

private fun Voice.composeOnce(
    topic: Topic,
    facts: Facts,
    age: Map<String, Int>,
    random: Random,
    anyLine: Boolean,
): Utterance? {
    for (recipe in topics[topic].orEmpty().shuffled(random)) {
        val parts = recipe.pick(facts, age, random, anyLine) ?: continue
        val fitted = parts.fit(topic.kind.maxLength) ?: continue
        if (fitted.isEmpty()) continue
        return Utterance(
            text = fitted.joinToString(" ") { it.text },
            lineIds = fitted.map { it.line.id },
        )
    }
    return null
}

/** Выбранный вариант уже с подставленными фактами. */
private class Part(val line: Line, val text: String, val optional: Boolean)

private fun Recipe.pick(facts: Facts, age: Map<String, Int>, random: Random, anyLine: Boolean): List<Part>? {
    val picked = mutableListOf<Part>()
    val usedKeys = HashSet<String>()
    for (slot in slots) {
        val usable = slot.lines.filter { line -> line.renders(facts) && line.keys.none(usedKeys::contains) }
        if (usable.isEmpty()) {
            if (slot.skip == 0.0) return null
            continue
        }
        if (slot.skip > 0.0 && random.nextDouble() < slot.skip) continue
        val line = (if (anyLine) usable else usable.oldest(age)).random(random)
        picked += Part(line, line.render(facts), optional = slot.skip > 0.0)
        usedKeys += line.keys
    }
    return picked
}

/** Ни разу не звучавшие, а если таких нет — старшая половина по давности. */
private fun List<Line>.oldest(age: Map<String, Int>): List<Line> {
    val never = filter { it.id !in age }
    if (never.isNotEmpty()) return never
    return sortedByDescending { age.getValue(it.id) }.take(maxOf(1, (size + 1) / 2))
}

/** Убирает самые длинные необязательные части, пока фраза не влезет. */
private fun List<Part>.fit(limit: Int): List<Part>? {
    val parts = toMutableList()
    while (parts.sumOf { it.text.length } + (parts.size - 1).coerceAtLeast(0) > limit) {
        val longest = parts.filter { it.optional }.maxByOrNull { it.text.length } ?: return null
        parts.remove(longest)
    }
    return parts
}
