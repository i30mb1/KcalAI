package n7.kcalai.mascot.voice

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import n7.kcalai.mascot.Facts
import n7.kcalai.model.Mascot
import n7.kcalai.model.Sex

/**
 * Как из вариантов складывается фраза.
 *
 * Голос здесь игрушечный: проверяется сборка, а не то, что говорят персонажи.
 */
class ComposerTest {

    /** Понедельник, 15:30. */
    private val facts = Facts(now = LocalDateTime(2026, 11, 2, 15, 30))

    private val pokes = listOf("Щекотно!", "Ай!", "Хи-хи.")

    private val toy = voice(Mascot.GRAIN) {
        topic(Topic.LONG_GAP) {
            recipe { slot { +"Уже {gap} без еды." } }
        }
        topic(Topic.POKE) {
            say { pokes.forEach { +it } }
        }
        topic(Topic.MEAL_LATE) {
            say { +"Ты {ел|ела} в полночь?" }
        }
        topic(Topic.COMEBACK) {
            recipe {
                slot { +"Привет, {name}!" }
                slot { +"Тебя не было {away}." }
            }
            recipe {
                slot { +"Тебя не было {away}." }
            }
        }
        topic(Topic.MEAL_OWNER) {
            say { +"{food} — и это я одобряю." }
        }
        topic(Topic.EMPTY_DAY) {
            recipe { slot { +"Последним было: {last}." } }
            recipe {
                slot(skip = 0.5) { +"Эй!" }
                slot { +"Сегодня в дневнике пусто." }
            }
        }
    }

    private fun compose(topic: Topic, facts: Facts, recent: Set<String> = emptySet(), seed: Int = 1) =
        toy.compose(topic, facts, recent, Random(seed))

    @Test
    fun `плейсхолдер подставляется из фактов`() {
        val said = compose(Topic.LONG_GAP, facts.copy(minutesSinceLastEntry = 200))

        assertEquals("Уже три часа без еды.", said?.text)
    }

    @Test
    fun `строка без нужного факта не выбирается`() {
        assertNull(compose(Topic.LONG_GAP, facts))
    }

    @Test
    fun `родовая строка — только когда пол известен`() {
        assertEquals("Ты ел в полночь?", compose(Topic.MEAL_LATE, facts.copy(sex = Sex.MALE))?.text)
        assertEquals("Ты ела в полночь?", compose(Topic.MEAL_LATE, facts.copy(sex = Sex.FEMALE))?.text)
        assertNull(compose(Topic.MEAL_LATE, facts))
    }

    @Test
    fun `недавно сказанное не повторяется, пока есть свежее`() {
        val recent = Line("Щекотно!").id to Line("Ай!").id

        repeat(20) { seed ->
            val said = compose(Topic.POKE, facts, setOf(recent.first, recent.second), seed)
            assertEquals("Хи-хи.", said?.text)
        }
    }

    @Test
    fun `всё сказано — повтор лучше молчания`() {
        val everything = pokes.map { Line(it).id }.toSet()

        val said = compose(Topic.POKE, facts, everything)

        assertTrue(said?.text in pokes)
    }

    @Test
    fun `рецепт с пустым обязательным слотом уступает следующему`() {
        repeat(20) { seed ->
            val said = compose(Topic.COMEBACK, facts.copy(daysAway = 3), seed = seed)
            assertEquals("Тебя не было три дня.", said?.text)
        }
    }

    @Test
    fun `тот же seed — та же фраза`() {
        val first = compose(Topic.POKE, facts, seed = 42)
        val second = compose(Topic.POKE, facts, seed = 42)

        assertEquals(first, second)
    }

    @Test
    fun `каждая часть начинается с заглавной`() {
        val said = compose(Topic.MEAL_OWNER, facts.copy(food = "Гречка отварная"))

        assertEquals("Гречка отварная — и это я одобряю.", said?.text)
    }

    @Test
    fun `свежая установка — без фактов фраза всё равно складывается`() {
        repeat(20) { seed ->
            val said = compose(Topic.EMPTY_DAY, facts, seed = seed)
            assertNotNull(said)
            assertTrue(said.text.endsWith("Сегодня в дневнике пусто."), said.text)
        }
    }

    @Test
    fun `необязательный слот иногда звучит, иногда нет`() {
        val texts = (0 until 40).mapNotNull { compose(Topic.EMPTY_DAY, facts, seed = it)?.text }.toSet()

        assertEquals(setOf("Сегодня в дневнике пусто.", "Эй! Сегодня в дневнике пусто."), texts)
    }

    @Test
    fun `сказанное помнится по вариантам`() {
        val said = compose(Topic.COMEBACK, facts.copy(daysAway = 2, name = "Женя"), seed = 3)

        assertNotNull(said)
        assertTrue(said.lineIds.isNotEmpty())
        assertTrue(said.lineIds.all { id -> id.length == 8 }, "id — восемь hex-знаков: ${said.lineIds}")
    }
}
