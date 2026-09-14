package n7.kcalai.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Один вариативный файл на все веса.
 *
 * Статические начертания стоили бы по 60–90 КБ каждое, а веса нужны три-четыре.
 * `variationSettings` работает с API 26, ровно с нашего minSdk, поэтому запасного
 * пути не предусмотрено: ниже 26 приложение не запускается вовсе.
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: Int) = Font(
    resId = resId,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Текст: заголовки, названия, подписи фразами. */
val Manrope = FontFamily(
    variable(R.font.manrope, 400),
    variable(R.font.manrope, 500),
    variable(R.font.manrope, 600),
    variable(R.font.manrope, 700),
)

/**
 * Все числа, время и капс-подписи.
 *
 * Моно здесь не стилизация: остаток до цели меняется с каждой записью, и в
 * пропорциональном наборе «519» и «1519» имеют разную ширину — число дёргается
 * на месте. Плюс капс-подписи моноширинным читаются как технический ярлык,
 * а не как ещё один заголовок, спорящий за внимание.
 */
val JetBrainsMono = FontFamily(
    variable(R.font.jetbrains_mono, 500),
    variable(R.font.jetbrains_mono, 700),
)

/**
 * Кегли и начертания экрана.
 *
 * Держится в одном объекте, а не размазано по композициям: шкала маленькая
 * (11–34sp), и любое «тут на полпункта крупнее» вне этого файла её разваливает.
 */
@Immutable
class KcalTypography {

    /** Подпись капсом: дата в шапке, «сегодня», «новое добавится сюда». */
    val caps = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.W500,
        fontSize = 11.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.14.em,
    )

    /** Метка плитки макроса: «БЕЛКИ». Плотнее и жирнее — она короткая и мелкая. */
    val capsTile = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.W700,
        fontSize = 10.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.1.em,
    )

    /** «Дневник». */
    val title = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.W600,
        fontSize = 17.sp,
        lineHeight = 20.sp,
    )

    /** Название приёма в пузырьке, заголовок реплики приложения, заголовок шита. */
    val bubbleTitle = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    )

    /** Фраза в реплике приложения. */
    val body = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.W400,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    )

    /** Чипс продукта и чипс подсказки. */
    val chip = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.W500,
        fontSize = 12.5.sp,
        lineHeight = 14.sp,
    )

    /** Подпись рядом с числом: «осталось», «перебор». */
    val label = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.W500,
        fontSize = 11.sp,
        lineHeight = 13.sp,
    )

    /** Строка ввода и кнопки. */
    val input = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.W500,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    )

    /** Остаток до цели — единственное крупное число на экране. */
    val hero = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.W700,
        fontSize = 30.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.03).em,
    )

    /** Число, которое правят прямо сейчас: вес в шите записи, калории в шите цели. */
    val heroInput = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.W700,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.03).em,
    )

    /** Значение на плитке макроса: «101/140». */
    val number = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.W700,
        fontSize = 15.sp,
        lineHeight = 17.sp,
    )

    /** Калории приёма в заголовке пузырька, ккал на чипсе. */
    val numberSmall = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.W700,
        fontSize = 11.sp,
        lineHeight = 13.sp,
    )

    /** Строка макросов пузырька: «Б 65 Ж 40 У 52». */
    val macro = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.W500,
        fontSize = 10.5.sp,
        lineHeight = 12.sp,
    )

    /** Время в углу пузырька. */
    val time = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.W500,
        fontSize = 10.sp,
        lineHeight = 11.sp,
    )
}

/** Радиусы. Названы по элементу: подбирать их заново в каждом месте — верный способ разъехаться. */
object KcalShapes {
    val chip = 13.dp
    val tile = 14.dp
    val bubble = 20.dp

    /** Срезанный угол пузырька — тот, что смотрит на своего автора. */
    val bubbleTail = 7.dp
    val input = 24.dp
    val sheet = 26.dp
}
