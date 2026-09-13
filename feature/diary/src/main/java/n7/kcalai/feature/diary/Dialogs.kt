package n7.kcalai.feature.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import n7.kcalai.personal.TdeeEstimate
import n7.kcalai.personal.TdeeEstimator

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
