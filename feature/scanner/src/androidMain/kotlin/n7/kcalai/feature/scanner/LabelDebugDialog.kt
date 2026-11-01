package n7.kcalai.feature.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import n7.kcalai.repositories.LabelReading
import n7.kcalai.ui.KcalTheme

/**
 * Съёмка этикетки вхолостую — чтобы посмотреть, что вообще читается.
 *
 * Обычный путь к распознаванию идёт через промах штрих-кода, и проверить разбор
 * на конкретной пачке значит каждый раз найти товар, которого нет в базе. Здесь
 * то же распознавание запускается сразу и показывает себя целиком: строки,
 * маршрут разбора, что принято за ноль. В дневник отсюда не попадает ничего.
 */
@Composable
actual fun LabelDebugDialog(onRead: (LabelReading) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = rememberScanState(null)
    val deliver by rememberUpdatedState(onRead)

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed -> granted = allowed }

    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }
    var zoom by rememberSaveable { mutableFloatStateOf(LABEL_ZOOM) }

    ScanDialog(onDismiss) {
        Surface(Modifier.fillMaxSize(), color = KcalTheme.colors.bg) {
            Box(Modifier.fillMaxSize()) {
                if (granted) {
                    CameraFrames(zoom = zoom, onPinch = { factor -> zoom = pinchedZoom(zoom, factor) }) {
                        scanAnalysis(
                            context = context,
                            reading = { true },
                            onBarcode = state::onBarcode,
                            onFrame = state::onFrame,
                            outcome = { state.outcome(state.saved) },
                        )
                    }
                }

                DebugPane(
                    frame = state.frame,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(start = 8.dp, end = 8.dp, top = 56.dp)
                        .fillMaxHeight(0.8f),
                )

                ScanTopBar(
                    title = "разбор этикетки",
                    meta = "кадр ${state.seen}",
                    onDismiss = onDismiss,
                    modifier = Modifier.align(Alignment.TopCenter),
                    action = { ZoomPill(zoom) { zoom = nextZoomStop(zoom) } },
                )

                ScanActions(
                    primary = "В лог",
                    primaryEnabled = true,
                    onPrimary = { deliver(state.frame.reading) },
                    secondary = "Закрыть",
                    onSecondary = onDismiss,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp),
                )
            }
        }
    }
}

/**
 * Разбор целиком, поверх превью.
 *
 * Порядок сверху вниз — от вывода к основаниям: сначала что получилось, потом
 * почему именно так, и лишь в конце исходные строки. Смотрят сюда, когда разбор
 * ошибся, а искать причину снизу вверх дольше.
 */
@Composable
private fun DebugPane(frame: LabelFrame, modifier: Modifier = Modifier) {
    val reading = frame.reading
    val trace = reading.trace

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!frame.available) {
            Line("OCR на этом устройстве недоступен", Color(0xFFFF8A80))
            return@Column
        }

        Line(
            "ккал ${reading.draft.kcal100 ?: "—"} · Б ${reading.draft.prot100.grams()} · " +
                "Ж ${reading.draft.fat100.grams()} · У ${reading.draft.carb100.grams()}",
            if (reading.confident) Color(0xFFB9F6CA) else Color.White,
        )
        Line(
            "${trace.route.title} · ${if (reading.confident) "сошлось" else "не сошлось"}",
            Color(0xFFB0BEC5),
        )
        Line("кадр ${frame.size} · ${frame.elapsedMs} мс", Color(0xFFB0BEC5))

        Spacer(Modifier.size(4.dp))

        // Уверенность по каждому значению отдельно. Общий счётчик кадров тут
        // бесполезен: съёмка стоит ровно из-за одной строки, и видеть надо,
        // из-за какой именно, а не то, что «идёт».
        Line("согласие за ${frame.frames} кадров", Color(0xFFB0BEC5))
        frame.fields.forEach { (name, field) -> VoteRow(name, field) }

        Spacer(Modifier.size(4.dp))

        Line("таблица опознана: ${if (trace.tableFound) "да" else "нет"}", Color(0xFFB0BEC5))
        if (trace.readLabels.isNotEmpty()) {
            Line("по подписям: ${trace.readLabels.joinToString(", ")}", Color(0xFFB0BEC5))
        }
        if (trace.derivedLabels.isNotEmpty()) {
            // Не прочитано, а посчитано из остальных трёх по Этуотеру. Видеть это
            // надо: число верное, но взялось оно не с пачки.
            Line("досчитано по Этуотеру: ${trace.derivedLabels.joinToString(", ")}", Color(0xFF80D8FF))
        }
        if (trace.zeroedLabels.isNotEmpty()) {
            // Тот самый случай, ради которого панель и нужна: у сахара или газировки
            // подписей «Белки» и «Жиры» на этикетке нет вовсе, и ноль там — это
            // прочитанное, а не выдуманное. Отличить одно от другого по готовой
            // форме невозможно, а здесь видно прямо.
            Line("подписи нет, принято за ноль: ${trace.zeroedLabels.joinToString(", ")}", Color(0xFFFFE082))
        }
        if (reading.numbers.isNotEmpty()) {
            Line("числа: ${reading.numbers.joinToString(" ")}", Color(0xFFB0BEC5))
        }
        if (reading.names.isNotEmpty()) {
            Line("название: ${reading.names.joinToString(" / ")}", Color(0xFFB0BEC5))
        }

        Spacer(Modifier.size(4.dp))

        Line("строк: ${trace.lines.size}", Color(0xFFB0BEC5))
        trace.lines.forEach { Line("· $it", Color.White) }
    }
}

/** Одно значение: сколько кадров за него, полоской и цифрами. */
@Composable
private fun VoteRow(name: String, field: LabelConsensus.Field) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Line(name.padEnd(5), Color(0xFFB0BEC5))
        Box(Modifier.width(72.dp).height(4.dp).background(Color.White.copy(alpha = 0.2f))) {
            Box(
                Modifier
                    .fillMaxWidth(field.progress)
                    .height(4.dp)
                    .background(if (field.settled) Color(0xFFB9F6CA) else Color(0xFFFFE082))
            )
        }
        Spacer(Modifier.size(6.dp))
        Line("${field.votes}/${field.needed}", Color(0xFFB0BEC5))
    }
}

@Composable
private fun Line(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
}
