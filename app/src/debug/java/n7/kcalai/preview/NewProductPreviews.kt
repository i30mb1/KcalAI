package n7.kcalai.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import n7.kcalai.feature.diary.FeedItem
import n7.kcalai.feature.diary.NewProductForm
import n7.kcalai.feature.diary.NewProductPanel
import n7.kcalai.feature.diary.NotFoundBubble
import n7.kcalai.ui.KcalTheme

/*
 * Превью своего продукта из ненайденного слова: реплика в ленте и форма.
 * Только в debug: в релиз не попадают.
 */

@Preview(name = "Не нашлось — реплика Яйца", widthDp = 380)
@Composable
private fun NotFoundPreview() = KcalTheme {
    Column(Modifier.background(KcalTheme.colors.bg).padding(16.dp)) {
        NotFoundBubble(FeedItem.NotFound("мамин пирог"), onAsk = {}, onAdd = {})
    }
}

@Preview(name = "Форма — мамин пирог, только название", widthDp = 380)
@Composable
private fun EmptyFormPreview() = FormPreview(NewProductForm(name = "Мамин пирог"))

@Preview(name = "Форма — заполнена, без кода", widthDp = 380)
@Composable
private fun HomeFormPreview() = FormPreview(
    NewProductForm(name = "Мамин пирог", kcal = "320", prot = "6,5", fat = "14", carb = "42"),
)

@Preview(name = "Форма — с кодом из магазина", widthDp = 380)
@Composable
private fun PackedFormPreview() = FormPreview(
    NewProductForm(name = "Хлебцы ржаные", kcal = "310", prot = "11", fat = "2", carb = "57", fiber = "14", barcode = "4607001771302"),
)

@Preview(name = "Форма — опечатка в коде", widthDp = 380)
@Composable
private fun TypoFormPreview() = FormPreview(
    NewProductForm(name = "Хлебцы ржаные", kcal = "310", prot = "11", fat = "2", carb = "57", barcode = "4607001771303"),
)

@Composable
private fun FormPreview(form: NewProductForm) = KcalTheme {
    Column(Modifier.background(KcalTheme.colors.surface).padding(16.dp)) {
        NewProductPanel(form, onChange = {}, onScanCode = {}, onSave = {})
    }
}
