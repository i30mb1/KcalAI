package n7.kcalai.mascot.voice

import kotlin.random.Random
import n7.kcalai.mascot.Facts

/** Сказанное: текст и варианты, из которых он сложен, — чтобы в следующий раз их обойти. */
data class Utterance(val text: String, val lineIds: List<String>)

/**
 * Фраза на тему из вариантов голоса.
 *
 * Форма фразы выбирается случайно; в каждом месте — свежий вариант, если он есть,
 * иначе любой подходящий: повтор лучше молчания. Рецепт, у которого обязательному
 * месту нечего сказать по сегодняшним фактам, уступает следующему.
 *
 * @param recent id вариантов, сказанных недавно
 * @return `null`, если на эту тему по этим фактам сказать нечего
 */
fun Voice.compose(topic: Topic, facts: Facts, recent: Set<String>, random: Random): Utterance? {
    for (recipe in topics[topic].orEmpty().shuffled(random)) {
        val lines = recipe.pick(facts, recent, random) ?: continue
        if (lines.isEmpty()) continue
        return Utterance(
            text = lines.joinToString(" ") { it.render(facts) },
            lineIds = lines.map { it.id },
        )
    }
    return null
}

private fun Recipe.pick(facts: Facts, recent: Set<String>, random: Random): List<Line>? {
    val picked = mutableListOf<Line>()
    for (slot in slots) {
        val usable = slot.lines.filter { it.renders(facts) }
        if (usable.isEmpty()) {
            if (slot.skip == 0.0) return null
            continue
        }
        if (slot.skip > 0.0 && random.nextDouble() < slot.skip) continue
        val fresh = usable.filter { it.id !in recent }
        picked += fresh.ifEmpty { usable }.random(random)
    }
    return picked
}
