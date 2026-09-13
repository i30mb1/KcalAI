package n7.kcalai.feature.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.Nutriments
import n7.kcalai.model.ProductDraft
import n7.kcalai.personal.TdeeEstimate
import n7.kcalai.personal.TdeeEstimator
import n7.kcalai.repositories.NutrimentValidator

/**
 * Цель на день плюс — и это главное здесь — оценка реального расхода.
 *
 * Формулы вроде Миффлина-Сан Жеора считают расход по росту и весу и ошибаются
 * на ±20%. Строка [TdeeEstimate] считает его по тому, что человек реально съел
 * и что реально произошло с его весом, — и подставляется в цель одной кнопкой.
 */
@Composable
fun GoalDialog(
    goal: DailyGoalEntity?,
    tdee: TdeeEstimate?,
    onConfirm: (kcal: Int, prot: Int, fat: Int, carb: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var kcal by remember { mutableStateOf(goal?.kcal?.toString().orEmpty()) }
    var prot by remember { mutableStateOf(goal?.prot?.toString().orEmpty()) }
    var fat by remember { mutableStateOf(goal?.fat?.toString().orEmpty()) }
    var carb by remember { mutableStateOf(goal?.carb?.toString().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Цель на день") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(kcal, { kcal = it }, "Калории")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(prot, { prot = it }, "Белки, г", Modifier.weight(1f))
                    NumberField(fat, { fat = it }, "Жиры, г", Modifier.weight(1f))
                    NumberField(carb, { carb = it }, "Углеводы, г", Modifier.weight(1f))
                }
                TdeeRow(tdee) { kcal = it.kcalPerDay.toString() }
            }
        },
        confirmButton = {
            TextButton(
                // Калории обязательны, макросы — нет: без них считается только
                // остаток по калориям, и это уже полезно.
                enabled = kcal.toIntOrNull()?.let { it > 0 } == true,
                onClick = {
                    onConfirm(
                        kcal.toIntOrNull() ?: 0,
                        prot.toIntOrNull() ?: 0,
                        fat.toIntOrNull() ?: 0,
                        carb.toIntOrNull() ?: 0,
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Строка про расход.
 *
 * Пока данных мало, показывается не пустота и не выдуманное число, а объяснение,
 * чего именно не хватает: человек должен понимать, что приложение не сломано,
 * а честно ждёт двух недель взвешиваний.
 */
@Composable
private fun TdeeRow(tdee: TdeeEstimate?, onApply: (TdeeEstimate) -> Unit) {
    if (tdee == null) {
        Text(
            "Расход считается по весу и съеденному. Нужно ${TdeeEstimator.MIN_WEIGHT_DAYS}+ дней " +
                "взвешиваний и записей — тогда здесь появится ваша цифра вместо формулы.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column {
        Text(
            "Расход ≈ ${tdee.kcalPerDay} ккал/день (±${tdee.marginKcal}, по ${tdee.days} дням)",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            trendText(tdee.weightTrendGramsPerDay),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onApply(tdee) }, contentPadding = PaddingZero) {
            Text("Взять за цель")
        }
    }
}

/** Тренд в неделю, а не в день: 30 г/день человеку ни о чём не говорят, 210 г/неделю — говорят. */
private fun trendText(gramsPerDay: Int): String {
    val perWeek = (gramsPerDay * 7 / 100.0).roundToInt() * 10
    return when {
        perWeek > 0 -> "Вес растёт примерно на $perWeek г в неделю"
        perWeek < 0 -> "Вес падает примерно на ${-perWeek} г в неделю"
        else -> "Вес держится"
    }
}

@Composable
fun WeightDialog(onConfirm: (grams: Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val grams = value.replace(',', '.').toDoubleOrNull()?.let { (it * 1000).roundToInt() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вес сегодня") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Килограммы") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                )
                Text(
                    "Взвешивайтесь как получится — фильтр отделит воду от настоящей массы.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = grams != null && grams in MIN_WEIGHT_G..MAX_WEIGHT_G,
                onClick = { grams?.let(onConfirm) },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Правка веса уже добавленной записи.
 *
 * Помимо очевидной пользы это единственный полностью однозначный сигнал для памяти
 * личных порций: человек видел подставленное число и заменил его своим.
 */
@Composable
fun GramsEditor(
    entry: DiaryEntryEntity,
    onConfirm: (grams: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(entry.grams.toString()) }
    val grams = value.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.displayName) },
        text = {
            Column {
                NumberField(value, { value = it }, "Граммы")
                Text(
                    "${grams?.let { entry.kcal100.toLong() * it / 100 } ?: 0} ккал",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = grams != null && grams > 0,
                onClick = { grams?.let(onConfirm) },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Продукта не нашлось нигде — заводим его руками.
 *
 * Это основной исход скана, а не исключение, поэтому форма не выглядит наказанием:
 * заполненное здесь оседает локально и находится мгновенно навсегда, а копия уезжает
 * на сервер, чтобы следующий человек с этой же пачкой формы уже не увидел.
 *
 * [draft] — вход для v2, где таблицу с упаковки прочитает OCR. Сегодня он пуст,
 * и форма от этого не отличается от написанной без него.
 */
@Composable
fun NewProductDialog(
    gtin: String,
    draft: ProductDraft,
    onConfirm: (name: String, nutriments: Nutriments, servingG: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(draft.name.orEmpty()) }
    var kcal by remember { mutableStateOf(draft.kcal100?.toString().orEmpty()) }
    var prot by remember { mutableStateOf(draft.prot100.toGramsInput()) }
    var fat by remember { mutableStateOf(draft.fat100.toGramsInput()) }
    var carb by remember { mutableStateOf(draft.carb100.toGramsInput()) }
    var serving by remember { mutableStateOf(draft.servingG?.toString().orEmpty()) }

    // Незаполненный макрос — это ноль, а не отказ сохранять: у растительного масла
    // белков действительно нет, и заставлять человека печатать «0» незачем.
    val nutriments = kcal.toIntOrNull()?.let {
        Nutriments(
            kcal100 = it,
            prot100 = prot.toCentigrams() ?: 0,
            fat100 = fat.toCentigrams() ?: 0,
            carb100 = carb.toCentigrams() ?: 0,
        )
    }
    val check = nutriments?.let(NutrimentValidator::check)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый продукт") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Код $gtin не нашёлся. Заполните КБЖУ на 100 г с упаковки — " +
                        "дальше этот продукт будет находиться сразу.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )

                NumberField(kcal, { kcal = it }, "Калории на 100 г")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(prot, { prot = it }, "Белки, г", Modifier.weight(1f))
                    DecimalField(fat, { fat = it }, "Жиры, г", Modifier.weight(1f))
                    DecimalField(carb, { carb = it }, "Углеводы, г", Modifier.weight(1f))
                }
                NumberField(serving, { serving = it }, "Порция, г — необязательно")

                // Ошибка гасит кнопку, замечание — нет. Расходящиеся цифры бывают
                // напечатаны на реальной упаковке, и спорить с упаковкой мы не вправе.
                check?.error?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                check?.warning?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && check?.valid == true,
                onClick = {
                    nutriments?.let {
                        onConfirm(name.trim(), it, serving.toIntOrNull()?.takeIf { g -> g > 0 })
                    }
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Сотые грамма в то, что человек ожидает увидеть в поле: «12.34», но «5», а не «5.0». */
private fun Int?.toGramsInput(): String = when {
    this == null -> ""
    this % 100 == 0 -> (this / 100).toString()
    else -> (this / 100.0).toString()
}

/** Граммы с этикетки в сотые грамма модели. Запятая и точка равноправны — клавиатуры разные. */
private fun String.toCentigrams(): Int? =
    replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 100).roundToInt() }

/** Дробное поле для макросов: на упаковках сплошь «2,8 г», и округлять их до целых — терять данные. */
@Composable
private fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() || it == '.' || it == ',' }) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        ),
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter(Char::isDigit)) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
    )
}

private val PaddingZero = PaddingValues(0.dp)

private const val MIN_WEIGHT_G = 20_000
private const val MAX_WEIGHT_G = 400_000
