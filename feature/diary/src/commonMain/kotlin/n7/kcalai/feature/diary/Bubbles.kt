package n7.kcalai.feature.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import n7.kcalai.database.Choice
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.fiber
import n7.kcalai.database.totals
import n7.kcalai.mascot.MascotQuestion
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.Mascot
import n7.kcalai.model.MealType
import n7.kcalai.model.formatCentigrams
import n7.kcalai.model.formatFiber
import n7.kcalai.model.formatGrams
import n7.kcalai.model.formatHour
import n7.kcalai.model.genitive
import n7.kcalai.model.title
import n7.kcalai.personal.DayOutline
import n7.kcalai.personal.MealGap
import n7.kcalai.personal.PlanOption
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.ChipNumbers
import n7.kcalai.ui.KcalChip
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.ui.Macro
import n7.kcalai.ui.MacroDot
import n7.kcalai.ui.SproutIcon
import n7.kcalai.ui.colors
import n7.kcalai.ui.letter

/** Доля ширины, которую пузырёк не переступает: лента должна читаться как диалог. */
private const val BUBBLE_WIDTH = 0.88f

/** Поля пузырька — плотно, как в мессенджерах, но текст не липнет к краю. */
private val BUBBLE_PADDING_H = 12.dp
private val BUBBLE_PADDING_V = 8.dp

/** Между концом фразы и временем. */
private val TIME_GAP = 8.dp

/** Время чуть ниже строки, а не на её линии: так оно читается подписью, а не словом фразы. */
private val TIME_DROP = 3.dp

/**
 * Место персонажа у реплики. Крупнее, чем был аватар у каждой реплики:
 * теперь он один на всю ленту и может себе это позволить.
 */
private val AVATAR_WIDTH = 44.dp
private val AVATAR_HEIGHT = 37.dp

/** Персонаж на сцене пустого дня. */
private val STAGE_WIDTH = 92.dp
private val STAGE_HEIGHT = 77.dp

/**
 * Исходящий пузырёк: приём пищи.
 *
 * Тёмный и справа — то, что записал человек. Это не украшение: у дневника два
 * автора, и различать их цветом и стороной дешевле, чем подписывать каждую реплику.
 *
 * Внутри — ровно три строки: что за приём и сколько в нём калорий, из чего он
 * состоит, и сколько это в макросах. Ничего четвёртого в пузырёк не помещается,
 * и это ограничение полезное.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealBubble(item: FeedItem.Meal, onEntry: (DiaryEntryEntity) -> Unit) {
    val colors = KcalTheme.colors
    val type = KcalTheme.type

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Column(
            modifier = Modifier
                .fillMaxWidth(BUBBLE_WIDTH)
                .wrapContentWidth(Alignment.End)
                .speechObstacle("meal:${item.key}")
                .clip(
                    RoundedCornerShape(
                        topStart = KcalShapes.bubble,
                        topEnd = KcalShapes.bubble,
                        bottomEnd = KcalShapes.bubbleTail,
                        bottomStart = KcalShapes.bubble,
                    )
                )
                .background(colors.bubble)
                .padding(horizontal = BUBBLE_PADDING_H, vertical = BUBBLE_PADDING_V),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    item.meal.title,
                    style = type.bubbleTitle,
                    // Перекус приглушён: это не полноценный приём пищи, и ставить
                    // его в ленте вровень с обедом значило бы врать про день.
                    color = if (item.meal == MealType.SNACK) colors.onBubble2 else colors.onBubble,
                )
                Spacer(Modifier.width(9.dp))
                Text("${item.totals.kcal} ккал", style = type.numberSmall, color = colors.onBubble2)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                item.entries.forEach { entry ->
                    EntryChip(entry) { onEntry(entry) }
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                MacroValue(Macro.PROTEIN, item.totals.protCg, onBubble = true)
                Spacer(Modifier.width(12.dp))
                MacroValue(Macro.FAT, item.totals.fatCg, onBubble = true)
                Spacer(Modifier.width(12.dp))
                MacroValue(Macro.CARB, item.totals.carbCg, onBubble = true)
                Spacer(Modifier.width(12.dp))
                // Клетчатка — следом за углеводами, цветом хохолка Зёрнышка. Неизвестная
                // у всех продуктов приёма не показывается: «Кл 0» было бы неправдой.
                formatFiber(item.fiber)?.let { fiber ->
                    Text("Кл $fiber", style = type.macro.copy(fontWeight = FontWeight.W700), color = colors.fiber.onBubble)
                    Spacer(Modifier.width(12.dp))
                }
                Text(item.time, style = type.time, color = colors.onBubble2)
            }
        }

        // Подпись есть только когда пузырёк действительно активен. Если запись
        // создаст новый — указывать на существующий было бы ложью.
        if (item.active) {
            CapsLabel(
                text = "новое добавится сюда",
                modifier = Modifier.padding(top = 5.dp, end = 6.dp),
            )
        }
    }
}

/** Продукт внутри пузырька. Тап открывает правку — вес, приём пищи, удаление. */
@Composable
private fun EntryChip(entry: DiaryEntryEntity, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    val macro = dominantMacro(entry.totals())

    KcalChip(onClick = onClick, background = colors.chipOnBubble) {
        MacroDot(macro.colors().onBubble)
        Text(
            entry.displayName,
            style = KcalTheme.type.chip,
            color = colors.onBubble,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 7.dp),
        )
        ChipNumbers("· ${formatGrams(entry.grams)}", colors.onBubble2)
        // Росток у порции, которая всерьёз добавила клетчатки: видно, что сработало.
        if (entry.fiber().cg >= FIBER_RICH_PORTION_CG) {
            SproutIcon(colors.fiber.onBubble, Modifier.padding(start = 5.dp), size = 10.dp)
        }
    }
}

/** От трёх грамм клетчатки порция отмечается ростком — столько даёт тарелка гречки. */
private const val FIBER_RICH_PORTION_CG = 300

/** «Б 65» — буква и граммы одним цветом: подпись и значение здесь неразделимы. */
@Composable
private fun MacroValue(macro: Macro, centigrams: Int, onBubble: Boolean) {
    val palette = macro.colors()
    Text(
        "${macro.letter} ${formatCentigrams(centigrams)}",
        style = KcalTheme.type.macro,
        color = if (onBubble) palette.onBubble else palette.text,
    )
}

/**
 * Входящий пузырёк: то, что говорит приложение.
 *
 * Все шесть моделей персонализации приходят сюда — и это главная находка экрана.
 * Раньше каждая требовала себе баннер, карточку или строку, и они дрались за место
 * в шапке. Реплика в диалоге места не требует: она просто следующая.
 */
@Composable
fun IncomingBubble(
    speaking: FeedItem,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    /** Что встаёт справа от пузырька, как в мессенджере, — кнопка «поделиться». */
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = KcalTheme.colors

    // Место персонажа у нижнего края, как аватар в мессенджере: там, где хвостик
    // пузырька. Сам он рисуется слоем сверху и встаёт только к последней своей
    // реплике — у остальных место пустует.
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        if (speakerOf(speaking) != null) {
            Box(Modifier.size(width = AVATAR_WIDTH, height = AVATAR_HEIGHT).mascotAnchor(speaking.key))
            Spacer(Modifier.width(4.dp))
        }
        // Пузырёк облегает реплику, как в мессенджере, а не тянется на всю ширину:
        // короткое «Записал 82,4 кг» в широком пузыре — полэкрана пустоты.
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth(BUBBLE_WIDTH)
                .wrapContentWidth(Alignment.Start)
                .speechObstacle("bubble:${speaking.key}")
                .clip(
                    RoundedCornerShape(
                        topStart = KcalShapes.bubble,
                        topEnd = KcalShapes.bubble,
                        bottomEnd = KcalShapes.bubble,
                        bottomStart = KcalShapes.bubbleTail,
                    )
                )
                .background(colors.surface)
                .padding(horizontal = BUBBLE_PADDING_H, vertical = BUBBLE_PADDING_V),
        ) {
            Column(Modifier.weight(1f, fill = false)) { content() }

            // Крестик закрывает вопрос до завтра — и это обязательный ответ.
            // Вопрос, от которого нельзя отказаться, через неделю становится упрёком.
            if (onDismiss != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .offset(x = 8.dp, y = (-6).dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Скрыть",
                        tint = colors.text3,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

/** Фраза в реплике приложения. С [time] — время в углу, в строку с концом фразы. */
@Composable
private fun BubbleText(text: String, modifier: Modifier = Modifier, time: String? = null) {
    if (time != null) {
        TimedText(text, time, modifier)
    } else {
        Text(text, style = KcalTheme.type.body, color = KcalTheme.colors.text, modifier = modifier)
    }
}

/**
 * Фраза со временем в углу — как в мессенджере.
 *
 * Время не занимает своей строки: оно встаёт в строку с концом фразы, чуть ниже
 * её, а пузырёк при нужде становится чуть шире. Последняя строка длинная — время
 * уходит строкой ниже, к правому краю. Где кончается последняя строка, знает
 * только раскладка текста, поэтому она запоминается прямо во время замера.
 */
@Composable
private fun TimedText(text: String, time: String, modifier: Modifier = Modifier) {
    val colors = KcalTheme.colors
    val laidOut = remember { arrayOfNulls<TextLayoutResult>(1) }

    Layout(
        content = {
            Text(
                remember(text) { mailLinked(text) },
                style = KcalTheme.type.body,
                color = colors.text,
                onTextLayout = { laidOut[0] = it },
            )
            Text(time, style = KcalTheme.type.time, color = colors.text3)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val body = measurables[0].measure(constraints.copy(minWidth = 0))
        val stamp = measurables[1].measure(Constraints())
        val lastLineRight = laidOut[0]?.let { ceil(it.getLineRight(it.lineCount - 1)).toInt() } ?: body.width
        val slot = timeSlot(body.width, lastLineRight, stamp.width, TIME_GAP.roundToPx(), constraints.maxWidth)
        val stampY = if (slot.inline) {
            body[LastBaseline] + TIME_DROP.roundToPx() - stamp[LastBaseline]
        } else {
            body.height
        }
        val width = constraints.constrainWidth(slot.width)
        layout(width, constraints.constrainHeight(maxOf(body.height, stampY + stamp.height))) {
            body.place(0, 0)
            stamp.place(width - stamp.width, stampY)
        }
    }
}

/** Заголовок реплики приложения. */
@Composable
private fun BubbleTitle(text: String) {
    Text(text, style = KcalTheme.type.bubbleTitle, color = KcalTheme.colors.text)
}

/**
 * Сводка дня.
 *
 * Показывается, пока записей за день нет, — вместо пустого экрана с надписью
 * «Пока пусто». Пустой дневник это не ошибка и не повод молчать: утром человеку
 * полезнее всего услышать, сколько ему можно и как обычно выглядит его день.
 */
@Composable
fun SummaryBubble(item: FeedItem.Summary, onSetGoal: () -> Unit) {
    Column {
        SummaryText(item, onSetGoal)
        // Пустой первый день: места полно, и все трое выходят знакомиться крупно.
        if (item.intro) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Mascot.entries.forEach { mascot ->
                    Box(Modifier.size(width = STAGE_WIDTH, height = STAGE_HEIGHT).mascotAnchor(stageAnchor(mascot)))
                }
            }
        }
    }
}

@Composable
private fun SummaryText(item: FeedItem.Summary, onSetGoal: () -> Unit) {
    val colors = KcalTheme.colors

    IncomingBubble(item) {
        BubbleTitle(item.greeting)
        if (item.intro) {
            // Яйцо говорит само, двух других оно представляет: без этого
            // персонажи для новичка — просто картинки.
            BubbleText("Масло следит за жирами, Зёрнышко — за углеводами.", Modifier.padding(top = 4.dp))
            BubbleText(
                "Напиши, что было на тарелке, — например, «гречка 200». Цифры посчитаем сами.",
                Modifier.padding(top = 8.dp),
            )
        }
        item.outline?.let { outline ->
            BubbleText(outlineSentence(outline), Modifier.padding(top = 4.dp))
            DayOutlineBar(outline, Modifier.padding(top = 8.dp))
        }
        if (item.needsGoal) {
            KcalChip(onClick = onSetGoal, background = colors.bubble, modifier = Modifier.padding(top = 8.dp)) {
                Text("Задать цель", style = KcalTheme.type.chip, color = colors.onBubble)
            }
        }
    }
}

/**
 * Раскладка дня полоской.
 *
 * Приёмы идут по ходу дня и занимают доли по своим типичным калориям. Оттенки
 * одного цвета, а не разные цвета: это один день, разделённый на части, а не
 * четыре независимые категории.
 */
@Composable
private fun DayOutlineBar(outline: DayOutline, modifier: Modifier = Modifier) {
    val colors = KcalTheme.colors
    if (outline.totalKcal <= 0) return

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            outline.meals.forEachIndexed { index, meal ->
                Box(
                    Modifier
                        .weight(meal.kcal.toFloat())
                        .fillMaxWidth()
                        .height(7.dp)
                        .background(colors.text.copy(alpha = OUTLINE_ALPHA[index % OUTLINE_ALPHA.size]))
                )
            }
        }
        Text(
            outline.meals.joinToString("  ·  ") { "${mealNameLower(it.meal)} ${it.kcal}" },
            style = KcalTheme.type.time,
            color = colors.text3,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private val OUTLINE_ALPHA = floatArrayOf(0.85f, 0.6f, 0.4f, 0.25f)

/**
 * Идея 4: приём пищи, который человек, похоже, забыл записать.
 *
 * Формулировка намеренно нейтральная. «Ты не обедал» — упрёк, повод удалить
 * приложение, да ещё и с родом, который приложению неизвестен; «Обед был?» —
 * вопрос, на который есть три ответа в один тап и четвёртый, закрывающий его до завтра.
 *
 * Ответ тапом вопрос не убирает: чипсы гаснут, выбранный отмечен. Крестик — другое
 * дело: это просьба скрыть, и вопрос уходит.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GapBubble(
    item: FeedItem.Gap,
    onPick: (FoodCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    val gap: MealGap = item.gap
    val picked = item.picked

    IncomingBubble(item, onDismiss = onDismiss.takeIf { picked == null }) {
        BubbleTitle("${gap.meal.title} был?")
        BubbleText(
            "Обычно ты ${mealVerb(gap.meal)} около ${formatHour(gap.typicalHour)}, " +
                "а сегодня ${gap.meal.genitive} в дневнике нет. " +
                "Если был — добавь одним тапом.",
            Modifier.padding(top = 4.dp),
        )
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            gap.suggestions.forEach { candidate ->
                CandidateChip(candidate, chosen = picked?.let { it == candidate }) { onPick(candidate) }
            }
        }
    }
}

/**
 * Идея 5: остаток и чем его закрыть.
 *
 * Ничего не советует и не придумывает: варианты собраны из того, что человек уже
 * ест, порции — его собственные медианы, а отбор это точный перебор по остатку
 * калорий с максимизацией недобранного белка.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemainingBubble(item: FeedItem.Remaining, onPick: (PlanOption) -> Unit) {
    val plan = item.plan

    IncomingBubble(item) {
        BubbleText(
            buildString {
                append(item.mealTitle.replaceFirstChar(Char::uppercase))
                append(" остаётся ")
                append(plan.remainingKcal)
                append(" ккал")
                if (plan.remainingProtCg > 0) {
                    append(" и ")
                    append(formatCentigrams(plan.remainingProtCg))
                    append(" г белка")
                }
                append(". Из твоего обычного подходит:")
            }
        )
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            plan.options.forEach { option ->
                OptionChip(option) { onPick(option) }
            }
        }
    }
}

/**
 * Съедено больше цели. Констатация и обещание, а не оценка поступка.
 *
 * Про прошедший день — только итог: «завтра это учтётся» о вчерашнем дне
 * звучит странно, завтра уже наступило.
 */
@Composable
fun OverBubble(item: FeedItem.Over) {
    IncomingBubble(item) {
        BubbleText(
            if (item.past) {
                "В этот день цель закрыта с перебором в ${item.overKcal} ккал."
            } else {
                "Цель на сегодня закрыта с перебором в ${item.overKcal} ккал. " +
                    "Ничего страшного — завтра это учтётся в раскладке дня."
            }
        )
    }
}

/** Прошлый день без записей: что можно сделать прямо здесь. */
@Composable
fun EmptyDayBubble(item: FeedItem.EmptyDay) {
    IncomingBubble(item) {
        BubbleTitle(item.title)
        BubbleText(
            "Записей за этот день нет. Напиши, что было, — запишу сюда. " +
                "Приём выбери над строкой ввода.",
            Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Набранного нет в базе — Яйцо сразу предлагает добавить.
 *
 * Тап открывает форму с уже вписанным названием: цифры на 100 г и, если есть, штрих-код.
 * Реплика не исчезает, пока слово в строке ввода: закрыл форму — можно открыть снова.
 */
@Composable
fun NotFoundBubble(item: FeedItem.NotFound, onAsk: () -> Unit, onAdd: () -> Unit) {
    val colors = KcalTheme.colors

    IncomingBubble(item) {
        BubbleText(notFoundText(item.query, item.assist))
        KcalChip(onClick = onAdd, background = colors.bubble, modifier = Modifier.padding(top = 8.dp)) {
            Text("Добавить", style = KcalTheme.type.chip, color = colors.onBubble)
        }
        // Нейросеть — четвёртый выход, и только по просьбе: разбор идёт секунды
        // и греет телефон, а запускать его на каждую паузу в наборе незачем.
        if (item.assist == Assist.OFFER || item.assist == Assist.THINKING) {
            val thinking = item.assist == Assist.THINKING
            KcalChip(
                onClick = if (thinking) null else onAsk,
                background = if (thinking) colors.chip else colors.bubble,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    if (thinking) "Разбираю…" else "Разобрать нейросетью",
                    style = KcalTheme.type.chip,
                    color = if (thinking) colors.text3 else colors.onBubble,
                )
            }
        }
    }
}

/**
 * Вопрос к дневнику — справа и тёмным, как всё, что пишет человек.
 *
 * Свой пузырёк, а не [MealBubble]: у вопроса нет ни приёма, ни калорий,
 * а форма и сторона те же — это реплика того же собеседника.
 */
@Composable
fun QuestionBubble(item: FeedItem.Question) {
    val colors = KcalTheme.colors

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Row(
            modifier = Modifier
                .fillMaxWidth(BUBBLE_WIDTH)
                .wrapContentWidth(Alignment.End)
                .speechObstacle("question:${item.key}")
                .clip(
                    RoundedCornerShape(
                        topStart = KcalShapes.bubble,
                        topEnd = KcalShapes.bubble,
                        bottomEnd = KcalShapes.bubbleTail,
                        bottomStart = KcalShapes.bubble,
                    )
                )
                .background(colors.bubble)
                .padding(horizontal = BUBBLE_PADDING_H, vertical = BUBBLE_PADDING_V),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(item.text, style = KcalTheme.type.body, color = colors.onBubble, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(TIME_GAP))
            Text(item.time, style = KcalTheme.type.time, color = colors.onBubble2)
        }
    }
}

/** Ответ нейросети. Пока модель не написала ни слова — многоточие, а не пустой пузырёк. */
@Composable
fun AnswerBubble(item: FeedItem.Answer) {
    IncomingBubble(item) {
        when {
            item.text.isEmpty() -> BubbleText("…")
            item.done -> BubbleText(item.text, time = item.time)
            else -> BubbleText(item.text)
        }
    }
}

/** Вес записан. Подтверждение действия, у которого больше нет своего экрана. */
@Composable
fun WeightBubble(item: FeedItem.WeightLogged) {
    IncomingBubble(item) {
        BubbleText("Записал ${formatKg(item.grams)}", time = item.time)
    }
}

/** Ответ на действие — «Отправлено: 2 сессии». */
@Composable
fun ReplyBubble(item: FeedItem.Reply) {
    IncomingBubble(item) {
        BubbleText(item.text, time = item.time)
    }
}

/**
 * Запись удалена — и её можно вернуть.
 *
 * Удаление в шите идёт без вопроса «вы уверены?», а значит, отмена обязана
 * быть под рукой: случайный тап не должен стирать завтрак насовсем. Вернули —
 * реплика остаётся, а «Вернуть» гаснет с галочкой.
 */
@Composable
fun DeletedBubble(item: FeedItem.Deleted, onUndo: () -> Unit) {
    val colors = KcalTheme.colors

    IncomingBubble(item) {
        // Время — в строку с кнопкой, у правого края: своей строки ему не нужно.
        Column(Modifier.width(IntrinsicSize.Max)) {
            BubbleText("Удалил «${item.entry.displayName} · ${formatGrams(item.entry.grams)}»")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                AnswerChip("Вернуть", chosen = if (item.restored) true else null, primary = true, onClick = onUndo)
                Spacer(Modifier.weight(1f).widthIn(min = TIME_GAP))
                Text(item.time, style = KcalTheme.type.time, color = colors.text3)
            }
        }
    }
}

/**
 * Сообщение персонажа — из шторки или ответ на имя и согласие.
 *
 * Обычный входящий пузырёк со временем: персонаж в переписке такой же
 * собеседник, как в шторке, и его слова стоят там же, где были сказаны.
 */
@Composable
fun SaidBubble(item: FeedItem.Said) {
    val share = LocalShareText.current
    val link = item.share
    IncomingBubble(
        speaking = item,
        // Как в мессенджере: иконка справа от сообщения, а не кнопка в нём. Это не ответ,
        // а действие — она не гаснет, и делиться можно сколько угодно раз.
        trailing = link?.let { { ShareButton(onClick = { share.share(it) }) } },
    ) {
        BubbleText(item.text, time = item.time)
    }
}

/** Круглая кнопка «поделиться» рядом с сообщением. */
@Composable
private fun ShareButton(onClick: () -> Unit) {
    val colors = KcalTheme.colors
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Share, contentDescription = "Поделиться", tint = colors.text2, modifier = Modifier.size(18.dp))
    }
}

/**
 * Персонаж спрашивает — ответ одним тапом.
 *
 * Оба ответа равноправны: «Не скажу» и «Не надо» — такие же кнопки, а не мелкий
 * текст снизу. Вопрос, от которого неудобно отказаться, перестаёт быть вопросом.
 *
 * Ответили — вопрос остаётся в переписке: кнопки гаснут, выбранная с галочкой.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskBubble(item: FeedItem.Ask, onYes: () -> Unit, onNo: () -> Unit) {
    val (yes, no) = when (item.question) {
        MascotQuestion.NAME -> "Сказать" to "Не скажу"
        MascotQuestion.WRITE -> "Пишите" to "Не надо"
        MascotQuestion.TREADMILL -> "Подключить" to "Не надо"
    }
    val answered = item.choice

    IncomingBubble(item) {
        BubbleText(item.text, time = item.time)
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AnswerChip(yes, chosen = answered?.let { it == Choice.YES }, primary = true, onClick = onYes)
            AnswerChip(no, chosen = answered?.let { it == Choice.NO }, primary = false, onClick = onNo)
        }
    }
}

/**
 * Рядом кто-то свой — Зёрнышко зовёт отправить ему еду.
 *
 * Имя соседа — чипс: их бывает несколько, и выбор между ними делает тап,
 * а не список с заголовком.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NearbyBubble(item: FeedItem.Nearby, onSend: (String) -> Unit) {
    IncomingBubble(item) {
        BubbleText(nearbyOffer(item.people.map { it.name }))
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            item.people.forEach { person ->
                AnswerChip(person.name, chosen = null, primary = true, onClick = { onSend(person.id) })
            }
        }
    }
}

/**
 * Телефон рядом предлагает записать еду — ответ одним тапом.
 *
 * Устроено как вопрос персонажа, и теми же кнопками: отвеченное остаётся
 * в переписке, выбранная с галочкой, повторный тап не вешается вовсе.
 * `FlowRow`, а не `Row`: у чужой еды подписи бывают длинными.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfferBubble(item: FeedItem.Offer, onYes: () -> Unit, onNo: () -> Unit) {
    val answered = item.choice

    IncomingBubble(item) {
        BubbleText(peerOffer(item.fromName, item.food, item.grams), time = item.time)
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AnswerChip("Записать", chosen = answered?.let { it == Choice.YES }, primary = true, onClick = onYes)
            AnswerChip("Не надо", chosen = answered?.let { it == Choice.NO }, primary = false, onClick = onNo)
        }
    }
}

/**
 * Браузер просится в дневник — Яйцо спрашивает, пустить ли. Теми же кнопками,
 * что вопрос персонажа: отвеченное остаётся в переписке с отметкой выбора.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserBubble(item: FeedItem.Browser, onYes: () -> Unit, onNo: () -> Unit) {
    val answered = item.choice

    IncomingBubble(item) {
        BubbleText(browserAsks(item.label, item.code), time = item.time)
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AnswerChip("Пустить", chosen = answered?.let { it == Choice.YES }, primary = true, onClick = onYes)
            AnswerChip("Нет", chosen = answered?.let { it == Choice.NO }, primary = false, onClick = onNo)
        }
    }
}

/**
 * Кнопка ответа в реплике.
 *
 * Пока ответа нет — живая. Ответили — гаснут все, а выбранная отмечена галочкой:
 * лента — переписка, отвеченное в ней остаётся видно, но второй раз событие
 * не запускает.
 *
 * @param chosen `null` — ответа ещё нет; `true` — выбрали эту; `false` — другую
 * @param primary главный ответ: пока живой, он тёмный, как реплика человека
 */
@Composable
private fun AnswerChip(text: String, chosen: Boolean?, primary: Boolean, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    val open = chosen == null

    KcalChip(onClick = if (open) onClick else null, background = if (open && primary) colors.bubble else colors.chip) {
        Text(
            if (chosen == true) "✓ $text" else text,
            style = KcalTheme.type.chip,
            color = when {
                open && primary -> colors.onBubble
                open -> colors.text
                chosen == true -> colors.text2
                else -> colors.text3
            },
        )
    }
}

/**
 * Яйцо предлагает отправить новые продукты в общую базу.
 *
 * Без согласия в сборке для людей ничего не уходит. Ответ пузырёк не убирает: обе
 * кнопки гаснут, выбранная отмечена — после «Отправить» под ним встаёт итог.
 */
@Composable
fun OutboxBubble(item: FeedItem.Outbox, onSend: () -> Unit, onDecline: () -> Unit) {
    val answered = item.sending || item.done || item.declined
    IncomingBubble(item) {
        BubbleText(outboxOffer(item.count))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnswerChip(
                text = if (item.sending) "Отправляю…" else "Отправить",
                chosen = if (answered) item.done else null,
                primary = true,
                onClick = onSend,
            )
            AnswerChip(
                text = "Не сейчас",
                chosen = if (answered) item.declined else null,
                primary = false,
                onClick = onDecline,
            )
        }
    }
}

/**
 * Предсказанный продукт: тап добавляет его целиком, вместе с типичной порцией.
 *
 * @param chosen `null` — ответа ещё нет; иначе чипс погас, а выбранный отмечен галочкой
 */
@Composable
fun CandidateChip(candidate: FoodCandidate, chosen: Boolean? = null, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    val grams = candidate.servingG ?: 0
    val macro = dominantMacro(candidate.nutriments)

    KcalChip(onClick = if (chosen == null) onClick else null, background = colors.chip) {
        if (chosen == true) {
            Text("✓", style = KcalTheme.type.chip, color = colors.text2)
        } else {
            MacroDot(if (chosen == null) macro.colors().fill else colors.text3)
        }
        Text(
            candidate.displayName,
            style = KcalTheme.type.chip,
            color = when (chosen) {
                null -> colors.text
                true -> colors.text2
                false -> colors.text3
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 7.dp),
        )
        ChipNumbers("· ${formatGrams(grams)}", colors.text3)
    }
}

/** Вариант добора целиком: «Лосось 150 г + рис 100 г · 510». */
@Composable
private fun OptionChip(option: PlanOption, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    val macro = dominantMacro(option.totals)

    KcalChip(onClick = onClick, background = colors.chip) {
        MacroDot(macro.colors().fill)
        Text(
            option.items.joinToString(" + ") { "${it.displayName} ${formatGrams(it.servingG ?: 0)}" },
            style = KcalTheme.type.chip,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 7.dp).weight(1f, fill = false),
        )
        ChipNumbers("· ${option.totals.kcal}", colors.text3)
    }
}

/** Чья это переписка: «сегодня», «вчера», «четверг, 29 окт». Один на ленту. */
@Composable
fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CapsLabel(label, color = KcalTheme.colors.text2)
    }
}
