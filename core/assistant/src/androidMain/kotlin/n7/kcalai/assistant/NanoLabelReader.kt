package n7.kcalai.assistant

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide
import kotlin.math.roundToInt
import n7.kcalai.model.ProductDraft

/**
 * Таблица пищевой ценности так, как её видит Gemini Nano.
 *
 * Типизированный ответ, а не текст: модель заполняет поля по схеме, и разбирать
 * её прозу регулярками не приходится. Всё, чего не видно, — `null`: выдумать
 * число для пустого поля модель не должна, а проверить его было бы нечем.
 */
@Generable(description = "Nutrition facts per 100 g printed on a food package")
data class LabelFacts(
    @param:Guide(description = "Product name as printed on the package, or null if not visible")
    val name: String?,
    @param:Guide(description = "Energy per 100 g in kcal (not kJ), or null if not visible", minimum = 0.0, maximum = 900.0)
    val kcal: Double?,
    @param:Guide(description = "Protein per 100 g in grams, or null if not visible", minimum = 0.0, maximum = 100.0)
    val protein: Double?,
    @param:Guide(description = "Fat per 100 g in grams, or null if not visible", minimum = 0.0, maximum = 100.0)
    val fat: Double?,
    @param:Guide(description = "Carbohydrates per 100 g in grams, or null if not visible", minimum = 0.0, maximum = 100.0)
    val carbs: Double?,
)

/**
 * Второе мнение по этикетке, когда PP-OCR с голосованием кадров не справился.
 *
 * Не замена распознаванию, а подстраховка: камера по-прежнему читает, а ответ
 * модели встаёт только в пустые поля и только сошедшийся по Этуотеру — это
 * проверяет карточка, см. `ScanState.onVision`.
 */
class NanoLabelReader {

    private val model by lazy { Generation.getClient() }

    suspend fun read(label: Bitmap): ProductDraft? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return try {
            if (model.checkStatus() != FeatureStatus.AVAILABLE) return null
            val request = generateTypedContentRequest(
                generateContentRequest(ImagePart(label), TextPart(PROMPT)) {
                    temperature = 0f
                },
                LabelFacts::class,
            )
            model.generateContent(request).candidates.firstOrNull()?.response?.toDraft()
        } catch (error: Exception) {
            Log.w(TAG, "Gemini Nano не прочла этикетку", error)
            null
        }
    }

    private fun LabelFacts.toDraft() = ProductDraft(
        name = name?.trim()?.takeIf(String::isNotEmpty),
        kcal100 = kcal?.roundToInt(),
        prot100 = protein?.centigrams(),
        fat100 = fat?.centigrams(),
        carb100 = carbs?.centigrams(),
    )

    private fun Double.centigrams(): Int = (this * 100).roundToInt()

    private companion object {
        const val TAG = "NanoLabelReader"

        val PROMPT = """
            This is a photo of a food package. Read the nutrition table: values per 100 g.
            Labels are usually in Russian: "Белки" protein, "Жиры" fat, "Углеводы" carbohydrates,
            "Энергетическая ценность" or "ккал" energy. If energy is given both in kJ and kcal, take kcal.
            Report only what is printed. Leave a field null if you cannot read it.
        """.trimIndent()
    }
}
