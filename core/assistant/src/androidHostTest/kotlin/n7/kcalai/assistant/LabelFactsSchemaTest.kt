package n7.kcalai.assistant

import com.google.mlkit.genai.schema.guided.GenerableProvider
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Схему типизированного ответа ML Kit ищет через ServiceLoader. Не найдёт —
 * чтение этикетки падает на телефоне, и никакой тест на эмуляторе этого не
 * покажет: Gemini Nano там нет. Поэтому файл службы проверяется здесь.
 */
class LabelFactsSchemaTest {

    @Test
    fun `схема ответа по этикетке находится так же, как её ищет ML Kit`() {
        val providers = ServiceLoader.load(GenerableProvider::class.java, javaClass.classLoader)
            .filter { it.targetClass == LabelFacts::class }

        assertEquals(1, providers.size)
        assertEquals(
            listOf("name", "kcal", "protein", "fat", "carbs"),
            providers.single().getGenerableDetail().guideDetails.map { it.name },
        )
    }
}
