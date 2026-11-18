package n7.kcalai.feature.diary

import kotlin.math.roundToInt
import n7.kcalai.model.Mascot
import n7.kcalai.model.Nutriments
import n7.kcalai.repositories.BarcodeValidator

/**
 * Свой продукт из ненайденного слова — то, что набрано в форме.
 *
 * Поля — строки, как их набирают: «6,5» и «6.5» одинаково, пустая клетчатка —
 * «неизвестно», а не ноль. Штрих-код необязателен: у маминого пирога его нет,
 * а у пачки из магазина он есть, и с ним продукт найдут и другие.
 */
data class NewProductForm(
    val name: String = "",
    val kcal: String = "",
    val prot: String = "",
    val fat: String = "",
    val carb: String = "",
    val fiber: String = "",
    val barcode: String = "",
) {
    /** Цифры на 100 г; `null` — чего-то из калорий и макросов не хватает. */
    val nutriments: Nutriments?
        get() {
            val kcal100 = kcal.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            // Клетчатку можно не знать: пустое поле — не ноль, а честное «неизвестно».
            val fiber100 = if (fiber.isBlank()) null else centigramsOf(fiber) ?: return null
            return Nutriments(
                kcal100 = kcal100,
                prot100 = centigramsOf(prot) ?: return null,
                fat100 = centigramsOf(fat) ?: return null,
                carb100 = centigramsOf(carb) ?: return null,
                fiber100 = fiber100,
            )
        }

    /** Код, прошедший контрольную цифру; `null` — кода нет или он с опечаткой. */
    val gtin: String? get() = BarcodeValidator.normalize(barcode)

    /** Код вписан, но не сходится: такой не уходит ни в базу, ни к людям. */
    val barcodeWrong: Boolean get() = barcode.isNotBlank() && gtin == null

    val canSave: Boolean get() = name.isNotBlank() && nutriments != null && !barcodeWrong
}

/** Форма для ненайденного слова: название уже вписано — тем, что человек набрал. */
internal fun newProductFormFor(query: String): NewProductForm =
    NewProductForm(name = query.trim().replaceFirstChar(Char::uppercase))

/**
 * Реплика Яйца в форме — хозяина общей базы. Что вписывать, он не объясняет:
 * поля подписаны сами. Говорит, что будет с продуктом.
 */
internal fun newProductSpeech(form: NewProductForm): SheetLine = SheetLine(
    Mascot.EGG,
    if (form.gtin != null) "С кодом его найдут и другие — предложу поделиться."
    else "Цифры с упаковки на 100 г. Есть штрих-код — сними, найдут и другие.",
)

private fun centigramsOf(text: String): Int? =
    text.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 100).roundToInt() }
