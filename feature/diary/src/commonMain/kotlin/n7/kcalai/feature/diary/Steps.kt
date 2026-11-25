package n7.kcalai.feature.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import n7.kcalai.model.DEFAULT_HEIGHT_CM
import n7.kcalai.model.Mascot
import n7.kcalai.model.heightInput
import n7.kcalai.model.stepsGoalInput
import n7.kcalai.model.stepsLabel
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.MiniBar
import n7.kcalai.ui.StepsIcon
import n7.kcalai.ui.WideButton

/**
 * Шаги дня — тихая плашка по центру ленты, как отметка в переписке: следы, полоса к цели и «5 242/8 000».
 * Подписи «шаги» нет — их называют следы. Тап открывает цель шагов и рост.
 */
@Composable
fun StepsPill(item: FeedItem.Steps, onOpen: () -> Unit) {
    val palette = KcalTheme.colors.steps
    val shape = RoundedCornerShape(percent = 50)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(palette.fill.copy(alpha = PILL_ALPHA))
                .clickable(onClick = onOpen)
                .clearAndSetSemantics { contentDescription = "шаги ${stepsLabel(item.steps)} из ${stepsLabel(item.goal)}" }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepsIcon(palette.text)
            MiniBar(
                fraction = item.steps.toFloat() / item.goal.coerceAtLeast(1),
                fill = palette.fill,
                modifier = Modifier.padding(horizontal = 7.dp).width(PILL_BAR_WIDTH),
            )
            Text(stepsLabel(item.steps), style = KcalTheme.type.macro.copy(fontWeight = FontWeight.W700), color = palette.text)
            Text("/${stepsLabel(item.goal)}", style = KcalTheme.type.macro, color = palette.text)
        }
    }
}

/**
 * Цель шагов и рост — шит по тапу на плашку шагов. Говорит Зёрнышко: прогулки — его тема.
 * Подписей у полей нет — их называют единицы, «шагов» и «см». Рост меряет шаг дорожки, поэтому
 * он здесь, только если её сигнал ловили ([treadmill]); не знают — поле пустое, и шаг по среднему.
 */
@Composable
fun StepsSheet(goal: Int, heightCm: Int?, treadmill: Boolean, onConfirm: (goal: Int, heightCm: Int?) -> Unit, onDismiss: () -> Unit) {
    var steps by remember { mutableStateOf(goal.toString()) }
    var height by remember { mutableStateOf(heightCm?.toString().orEmpty()) }
    val picked = stepsGoalInput(steps)
    val cm = heightInput(height)
    val ready = picked != null && (cm != null || height.isEmpty())

    val anchors = remember { MascotAnchors() }
    val pointer = remember { StagePointer() }

    Sheet(onDismiss) {
    CompositionLocalProvider(LocalMascotAnchors provides anchors) {
    Box(Modifier.observeTouches(pointer)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetSpeech(if (treadmill) TREADMILL_LINE else STEPS_LINE, Modifier.padding(bottom = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StepsField(
                    value = steps,
                    onValueChange = { steps = it },
                    unit = "шагов",
                    placeholder = stepsLabel(goal),
                    tinted = true,
                    description = "цель шагов",
                    modifier = Modifier.weight(STEPS_FIELD_WEIGHT),
                    imeAction = if (treadmill) ImeAction.Next else ImeAction.Done,
                )
                if (treadmill) StepsField(
                    value = height,
                    onValueChange = { height = it.take(HEIGHT_DIGITS) },
                    unit = "см",
                    placeholder = DEFAULT_HEIGHT_CM.toString(),
                    tinted = false,
                    description = "рост",
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Done,
                )
            }
            Choices(STEP_PRESETS, picked, ::stepsLabel) { steps = it.toString() }
            WideButton(
                text = "Сохранить",
                enabled = ready,
                onClick = { picked?.let { onConfirm(it, cm) } },
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
            )
        }
        MascotStage(
            places = stepsSheetPlaces(),
            anchors = anchors,
            pointer = pointer,
            input = steps + height,
            speech = emptyList(),
            cue = null,
            night = false,
            onEvent = {},
            modifier = Modifier.matchParentSize(),
        )
    }
    }
    }
}

/** Поле шита шагов: число и единица; цель — в цвете шагов, рост — нейтральный. */
@Composable
private fun StepsField(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    placeholder: String,
    tinted: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
) {
    val colors = KcalTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(KcalShapes.tile))
            .background(if (tinted) colors.steps.tile else colors.chip)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tinted) StepsIcon(colors.steps.text, Modifier.padding(end = 6.dp), size = 14.dp)
        NumberInput(
            value = value,
            onValueChange = onValueChange,
            style = KcalTheme.type.number,
            placeholder = placeholder,
            imeAction = imeAction,
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
        )
        Text(" $unit", style = KcalTheme.type.macro, color = if (tinted) colors.steps.text else colors.text3)
    }
}

/** В шите шагов Зёрнышко у реплики, остальные за сценой: полей-домиков у них здесь нет. */
internal fun stepsSheetPlaces(): Map<Mascot, MascotPlace> =
    Mascot.entries.associateWith { mascot ->
        if (mascot == Mascot.GRAIN) MascotPlace.Reply(SHEET_SPEECH_ANCHOR) else MascotPlace.Offstage
    }

private val STEPS_LINE = SheetLine(Mascot.GRAIN, "Сколько шагов в день берём? Выбери или впиши своё.")
private val TREADMILL_LINE = SheetLine(Mascot.GRAIN, "Сколько шагов в день берём? По росту мерю шаг на дорожке.")
private val STEP_PRESETS = listOf(6_000, 8_000, 10_000, 12_000)
private const val HEIGHT_DIGITS = 3
private const val STEPS_FIELD_WEIGHT = 1.4f
private const val PILL_ALPHA = 0.12f
private val PILL_BAR_WIDTH = 44.dp
