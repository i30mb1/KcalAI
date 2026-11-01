package n7.kcalai.mascot.voice

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import n7.kcalai.mascot.Chronotype
import n7.kcalai.mascot.Facts
import n7.kcalai.mascot.KNOWN_KEYS
import n7.kcalai.mascot.MissedMeal
import n7.kcalai.model.MealType
import n7.kcalai.model.Mascot
import n7.kcalai.model.Mascot.BUTTER
import n7.kcalai.model.Mascot.EGG
import n7.kcalai.model.Mascot.GRAIN
import n7.kcalai.model.Sex

/**
 * Что и как говорят трое.
 *
 * Тексты пишутся руками, и ошибки в них — не опечатки, а поломки иллюзии:
 * «ты ел» женщине, «{gap}» в шторке, выдуманная цифра, упрёк за ужин.
 * Всё это ловится здесь, а не человеком.
 */
class VoicesTest {

    private val expectedCast: Map<Topic, Set<Mascot>> = buildMap {
        Topic.entries.forEach { put(it, setOf(EGG, BUTTER, GRAIN)) }
        put(Topic.LONG_GAP, setOf(BUTTER, GRAIN))
        put(Topic.EMPTY_DAY, setOf(EGG, GRAIN))
        put(Topic.STREAK_KEEP, setOf(EGG))
        put(Topic.PROTEIN_LEFT, setOf(EGG))
        put(Topic.YESTERDAY_WIN, setOf(BUTTER))
        put(Topic.NAME_THANKS, setOf(EGG))
        put(Topic.WRITE_YES, setOf(GRAIN))
        put(Topic.WRITE_NO, setOf(GRAIN))
        put(Topic.WRITE_DENIED, setOf(GRAIN))
        put(Topic.GOAL_REACHED, setOf(EGG, BUTTER))
    }

    /** Факты, при которых у каждой темы есть о чём сказать. */
    private fun rich(
        hour: Int = 15,
        day: Int = 2,
        name: String? = "Женя",
        sex: Sex? = null,
        daysTogether: Int = 10,
        chronotype: Chronotype? = null,
        food: String = "Гречка отварная",
    ) = Facts(
        now = LocalDateTime(2026, 11, day, hour, 30),
        name = name,
        sex = sex,
        daysTogether = daysTogether,
        minutesSinceLastEntry = 200,
        lastFood = food,
        lastEntryAt = LocalTime(12, 40),
        todayEntries = 2,
        missed = MissedMeal(MealType.LUNCH, usualHour = 13, usualFood = food, usualGrams = 200),
        protLeftG = 40,
        protIdea = "Творог 5%",
        streakDays = 6,
        daysAway = 4,
        favorite = food,
        yesterdayOnGoal = true,
        chronotype = chronotype,
        absentMinutes = 300,
        food = food,
        foodDaysInRow = 3,
    )

    /** Самые длинные факты, какие бывают: имя в двадцать букв, название — в предел. */
    private val longest = rich(name = "Александра-Виктория", food = "Творог мягкий обезжиренный «Простоквашино»")

    /** День и ночь, будни и выходные, знакомые и новенькие, с именем и без, с полом и без. */
    private val grid: List<Facts> = buildList {
        for (hour in listOf(7, 13, 19, 23)) for (day in listOf(2, 7)) for (sex in listOf(null, Sex.MALE, Sex.FEMALE))
            for (name in listOf(null, "Женя")) for (together in listOf(1, 10, 60))
                for (chronotype in listOf(null, Chronotype.LARK, Chronotype.OWL))
                    add(rich(hour = hour, day = day, name = name, sex = sex, daysTogether = together, chronotype = chronotype))
    }

    private val allLines: List<Line> = Mascot.entries.flatMap { mascot ->
        Voices.of(mascot).topics.values.flatten().flatMap { it.slots }.flatMap { it.lines }
    }

    @Test
    fun `кто говорит на какую тему`() {
        assertEquals(expectedCast, Voices.cast)
    }

    @Test
    fun `каждый из состава говорит на свою тему при любых фактах дня`() {
        for ((topic, cast) in expectedCast) for (mascot in cast) for (day in grid) {
            // Спасибо за имя говорят, только когда имя назвали.
            val facts = if (topic == Topic.NAME_THANKS) day.copy(name = day.name ?: "Женя") else day
            assertNotNull(
                Voices.of(mascot).compose(topic, facts, emptyList(), Random(facts.hashCode())),
                "$mascot молчит на $topic при $facts",
            )
        }
    }

    @Test
    fun `ни одного незакрытого плейсхолдера`() {
        forEverySaying(rich()) { topic, mascot, text ->
            assertTrue('{' !in text && '}' !in text, "$mascot, $topic: $text")
        }
    }

    @Test
    fun `реплика укладывается в длину своей темы`() {
        forEverySaying(longest) { topic, mascot, text ->
            assertTrue(text.length <= topic.kind.maxLength, "$mascot, $topic, ${text.length}: $text")
        }
    }

    @Test
    fun `в вариантах нет цифр`() {
        allLines.forEach { assertTrue(it.text.none(Char::isDigit), "цифра в «${it.text}»") }
    }

    /** Ни упрёка, ни диеты, ни запретов — персонажи не судят еду и тело. */
    @Test
    fun `в вариантах нет запретных корней`() {
        for (line in allLines) {
            val text = line.text.lowercase()
            BANNED.firstOrNull { it in text }?.let { fail("«$it» в «${line.text}»") }
        }
    }

    @Test
    fun `родовые формы только в альтернативах`() {
        for (line in allLines) {
            val plain = TOKEN.replace(line.text, " ").lowercase()
            val words = WORD.findAll(plain).map { it.value }.toSet()
            val gendered = words intersect GENDERED
            assertTrue(gendered.isEmpty(), "$gendered без {м|ж} в «${line.text}»")
        }
    }

    @Test
    fun `в альтернативе ровно две формы`() {
        for (line in allLines) for (match in TOKEN.findAll(line.text)) {
            val body = match.groupValues[1]
            if ('|' in body) assertEquals(2, body.split('|').size, "«${line.text}»")
        }
    }

    @Test
    fun `плейсхолдеры только известные`() {
        for (line in allLines) for (match in TOKEN.findAll(line.text)) {
            val body = match.groupValues[1]
            if ('|' !in body) assertTrue(body in KNOWN_KEYS, "{$body} в «${line.text}»")
        }
    }

    /** id — отпечаток текста; у двух разных текстов он совпасть не должен. */
    @Test
    fun `id вариантов не повторяются`() {
        allLines.groupBy { it.id }.forEach { (id, lines) ->
            assertEquals(1, lines.map { it.text }.toSet().size, "один id $id у разных текстов: $lines")
        }
    }

    /**
     * «Каждый раз уникальные фразы»: двести сообщений на одну тему с одинаковыми
     * фактами — и почти все разные, а в любых двадцати подряд повторов нет вовсе.
     */
    @Test
    fun `двести сообщений подряд — почти все разные`() {
        val notes = expectedCast.filterKeys { it.kind == TopicKind.NOTE }
        for ((topic, cast) in notes) for (mascot in cast) {
            val random = Random(7)
            val memory = ArrayDeque<List<String>>()
            val texts = mutableListOf<String>()
            repeat(200) {
                val said = Voices.of(mascot).compose(topic, rich(), memory.reversed(), random)
                    ?: fail("$mascot молчит на $topic")
                texts += said.text
                memory.addLast(said.lineIds)
                if (memory.size > MEMORY) memory.removeFirst()
            }
            val unique = texts.toSet().size
            assertTrue(unique >= 180, "$mascot, $topic: разных $unique из 200")
            texts.windowed(20).forEach { window ->
                assertEquals(window.size, window.toSet().size, "$mascot, $topic: повтор в двадцати подряд")
            }
        }
    }

    /** Облачко и ответ — короткие, но первые шесть подряд не повторяются. */
    @Test
    fun `первые шесть реплик на тему — все разные`() {
        val short = expectedCast.filterKeys { it.kind != TopicKind.NOTE }
        for ((topic, cast) in short) for (mascot in cast) {
            val random = Random(3)
            val recent = mutableListOf<List<String>>()
            val texts = List(6) {
                val said = Voices.of(mascot).compose(topic, rich(), recent, random) ?: fail("$mascot молчит на $topic")
                recent.add(0, said.lineIds)
                said.text
            }
            assertEquals(6, texts.toSet().size, "$mascot, $topic: $texts")
        }
    }

    private fun forEverySaying(facts: Facts, check: (Topic, Mascot, String) -> Unit) {
        for ((topic, cast) in expectedCast) for (mascot in cast) repeat(60) { seed ->
            val said = Voices.of(mascot).compose(topic, facts, emptyList(), Random(seed)) ?: fail("$mascot молчит на $topic")
            check(topic, mascot, said.text)
        }
    }

    private companion object {
        const val MEMORY = 40

        val BANNED = listOf(
            "обжор", "толст", "жирдя", "позор", "стыд", "срыв", "сорвал", "диет", "похуд",
            "вредн", "нельзя", "запрет", "лишн", "виноват", "наказ",
        )

        /** Формы, у которых есть род человека. О себе персонажи говорят иначе. */
        val GENDERED = setOf(
            "ел", "ела", "съел", "съела", "поел", "поела", "забыл", "забыла", "пропал", "пропала",
            "устал", "устала", "вернулся", "вернулась", "обедал", "обедала", "ужинал", "ужинала",
            "завтракал", "завтракала", "перекусил", "перекусила", "голоден", "голодна", "сыт", "сыта",
            "занят", "занята", "готов", "готова", "уверен", "уверена", "должен", "должна", "рад", "рада",
            "проснулся", "проснулась", "заглянул", "заглянула", "пришёл", "пришла", "видал", "видала",
            "прочитал", "прочитала", "лучший", "лучшая",
        )

        val WORD = Regex("""[а-яё]+""")
    }
}
