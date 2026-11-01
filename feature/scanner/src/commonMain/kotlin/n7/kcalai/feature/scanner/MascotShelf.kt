package n7.kcalai.feature.scanner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.colors
import n7.kcalai.ui.mascot.MascotFigure
import n7.kcalai.ui.mascot.MascotPose
import n7.kcalai.ui.mascot.macro
import n7.kcalai.ui.popIn

/**
 * Трое над камерой: у каждого свой пузырёк и своя поза.
 *
 * Выходят по очереди, слева направо, — так видно, что их трое и у каждого своё.
 * Места под них заняты сразу: иначе ряд дёргался бы, пока они выходят.
 * Высота у ряда постоянная по той же причине — пузырьки меняют текст, а камера
 * под ними не должна прыгать.
 */
@Composable
internal fun MascotShelf(lines: List<MascotLine>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SHELF_HEIGHT)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        lines.forEachIndexed { index, line ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                ComesOut(delayMs = index * STAGGER_MS) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SpeechBubble(line)
                        // Новое настроение — новая поза, и персонаж заново машет: так
                        // видно, кто сейчас заговорил, даже краем глаза над пачкой.
                        key(line.mood) {
                            MascotFigure(
                                mascot = line.mascot,
                                pose = line.mood.pose,
                                modifier = Modifier.size(width = FIGURE_WIDTH, height = FIGURE_HEIGHT),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Получил своё — сидит, поджав ручки. Просит или зовёт — поднимает руку. */
private val MascotMood.pose: MascotPose
    get() = when (this) {
        MascotMood.WAITING -> MascotPose.HOME
        MascotMood.ASKING, MascotMood.TYPING, MascotMood.DONE -> MascotPose.WAVE
    }

/** Пружинный выход снизу — после паузы, чтобы трое выходили по очереди. */
@Composable
private fun ComesOut(delayMs: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(delayMs == 0) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        shown = true
    }
    if (shown) {
        Box(Modifier.popIn(origin = TransformOrigin(0.5f, 1f), lift = 14.dp)) { content() }
    }
}

/**
 * Пузырёк над персонажем.
 *
 * Пока персонаж ждёт своё — пузырёк белый, как входящая реплика в ленте. Получил —
 * пузырёк становится цвета его макроса: так с одного взгляда видно, чьё уже есть.
 */
@Composable
private fun SpeechBubble(line: MascotLine) {
    val colors = KcalTheme.colors
    val macro = line.mascot.macro.colors()
    val settled = line.mood == MascotMood.WAITING || line.mood == MascotMood.DONE
    val background by animateColorAsState(if (settled) macro.tile else colors.surface, tween(COLOR_MS))
    val ink by animateColorAsState(if (settled) macro.text else colors.text, tween(COLOR_MS))
    val shape = RoundedCornerShape(14.dp)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BUBBLE_HEIGHT)
                // Тень отделяет пузырёк от живой картинки: под ним не фон, а кадр.
                .shadow(3.dp, shape)
                .clip(shape)
                .background(background)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = line.text,
                transitionSpec = { fadeIn(tween(220, delayMillis = 80)) togetherWith fadeOut(tween(120)) },
                label = "bubble",
            ) { text ->
                Text(
                    text,
                    style = KcalTheme.type.label.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    color = ink,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BubbleTail(background)
    }
}

/** Хвостик пузырька — вниз, к персонажу. */
@Composable
private fun BubbleTail(color: Color) {
    Canvas(Modifier.size(width = 12.dp, height = 6.dp)) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width / 2, size.height)
            close()
        }
        drawPath(path, color)
    }
}

/** Ряд целиком: пузырёк, хвостик, персонаж. Камера под ним считает от этой высоты. */
internal val SHELF_HEIGHT = 126.dp

/** Три строки реплики целиком: в 58 dp третья не помещалась и обрезалась многоточием. */
private val BUBBLE_HEIGHT = 66.dp

/** Пропорция персонажа — 6:5, как у его холста. */
private val FIGURE_WIDTH = 60.dp
private val FIGURE_HEIGHT = 50.dp

private const val STAGGER_MS = 140
private const val COLOR_MS = 300
