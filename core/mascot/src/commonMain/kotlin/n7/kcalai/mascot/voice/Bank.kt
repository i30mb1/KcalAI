package n7.kcalai.mascot.voice

import n7.kcalai.mascot.Facts
import n7.kcalai.model.Mascot

/**
 * Место во фразе — зачин, суть, личное, концовка — и варианты для него.
 *
 * @param skip вероятность промолчать в этом месте. Ноль — слот обязателен:
 *        без него фраза не складывается, и рецепт уступает следующему.
 */
class Slot(val lines: List<Line>, val skip: Double)

/** Порядок слотов — одна из форм фразы на тему. У темы их несколько, чтобы и строй фразы не повторялся. */
class Recipe(val slots: List<Slot>)

/** Всё, что умеет сказать один персонаж, по темам. */
class Voice(val mascot: Mascot, val topics: Map<Topic, List<Recipe>>)

@DslMarker
annotation class VoiceDsl

/**
 * Голос персонажа.
 *
 * ```
 * voice(Mascot.GRAIN) {
 *     val hey = pool { +"Эй!"; "Доброе утро!" given { it.partOfDay == PartOfDay.MORNING } }
 *     topic(Topic.LONG_GAP) {
 *         recipe {
 *             slot(hey, skip = 0.4)
 *             slot { +"Уже {gap} без еды." }
 *         }
 *     }
 *     topic(Topic.POKE) { say { +"Щекотно!" } }
 * }
 * ```
 */
fun voice(mascot: Mascot, block: VoiceBuilder.() -> Unit): Voice = VoiceBuilder(mascot).apply(block).build()

@VoiceDsl
class VoiceBuilder internal constructor(private val mascot: Mascot) {
    private val topics = LinkedHashMap<Topic, MutableList<Recipe>>()

    /** Общий для нескольких тем набор: зачины, концовки. */
    fun pool(block: LinesBuilder.() -> Unit): List<Line> = LinesBuilder().apply(block).lines.toList()

    fun topic(topic: Topic, block: TopicBuilder.() -> Unit) {
        topics.getOrPut(topic) { mutableListOf() } += TopicBuilder().apply(block).recipes
    }

    internal fun build(): Voice = Voice(mascot, topics.mapValues { (_, recipes) -> recipes.toList() })
}

@VoiceDsl
class TopicBuilder internal constructor() {
    internal val recipes = mutableListOf<Recipe>()

    fun recipe(block: RecipeBuilder.() -> Unit) {
        recipes += RecipeBuilder().apply(block).build()
    }

    /** Фраза из одного слота — для облачков и коротких ответов. */
    fun say(block: LinesBuilder.() -> Unit) = recipe { slot(block = block) }

    /** Набор вариантов темы, общий для нескольких её рецептов. */
    fun pool(block: LinesBuilder.() -> Unit): List<Line> = LinesBuilder().apply(block).lines.toList()
}

@VoiceDsl
class RecipeBuilder internal constructor() {
    private val slots = mutableListOf<Slot>()

    fun slot(skip: Double = 0.0, block: LinesBuilder.() -> Unit) {
        slots += Slot(LinesBuilder().apply(block).lines.toList(), skip)
    }

    fun slot(lines: List<Line>, skip: Double = 0.0) {
        slots += Slot(lines, skip)
    }

    internal fun build(): Recipe = Recipe(slots.toList())
}

@VoiceDsl
class LinesBuilder internal constructor() {
    internal val lines = mutableListOf<Line>()

    operator fun String.unaryPlus() {
        lines += Line(this)
    }

    /** Вариант с условием: «Доброе утро!» — только утром. */
    infix fun String.given(fits: (Facts) -> Boolean) {
        lines += Line(this, fits)
    }

    /** Добавить общий набор к своим вариантам. */
    fun include(pool: List<Line>) {
        lines += pool
    }
}
