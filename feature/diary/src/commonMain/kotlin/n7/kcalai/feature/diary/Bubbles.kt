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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.totals
import n7.kcalai.model.FoodCandidate
import n7.kcalai.model.MealType
import n7.kcalai.personal.DayOutline
import n7.kcalai.personal.MealGap
import n7.kcalai.personal.PlanOption
import n7.kcalai.ui.CapsLabel
import n7.kcalai.ui.ChipNumbers
import n7.kcalai.ui.KcalChip
import n7.kcalai.ui.Macro
import n7.kcalai.ui.MacroDot
import n7.kcalai.model.Mascot
import n7.kcalai.ui.colors
import n7.kcalai.ui.letter
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme
import n7.kcalai.model.genitive
import n7.kcalai.model.title

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
    }
}

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
            Text(text, style = KcalTheme.type.body, color = colors.text, onTextLayout = { laidOut[0] = it })
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
        item.weekLine?.let { BubbleText(it, Modifier.padding(top = 4.dp)) }
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
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GapBubble(
    item: FeedItem.Gap,
    onPick: (FoodCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    val gap: MealGap = item.gap

    IncomingBubble(item, onDismiss = onDismiss) {
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
                CandidateChip(candidate) { onPick(candidate) }
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
 * Набранное не находится.
 *
 * Раньше это была серая строчка в композере — то есть сообщение о неудаче
 * в том самом месте, куда человек смотрит, пока печатает. Репликой оно
 * превращается в ответ собеседника и называет все три выхода.
 */
@Composable
fun NotFoundBubble(item: FeedItem.NotFound) {
    IncomingBubble(item) {
        BubbleText(notFoundText(item.query))
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
 * быть под рукой: случайный тап не должен стирать завтрак насовсем.
 */
@Composable
fun DeletedBubble(item: FeedItem.Deleted, onUndo: () -> Unit) {
    val colors = KcalTheme.colors

    IncomingBubble(item) {
        // Время — в строку с кнопкой, у правого края: своей строки ему не нужно.
        Column(Modifier.width(IntrinsicSize.Max)) {
            BubbleText("Удалил «${item.entry.displayName} · ${formatGrams(item.entry.grams)}»")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                KcalChip(onClick = onUndo, background = colors.bubble) {
                    Text("Вернуть", style = KcalTheme.type.chip, color = colors.onBubble)
                }
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
    IncomingBubble(item) {
        BubbleText(item.text, time = item.time)
    }
}

/**
 * Персонаж спрашивает — ответ одним тапом.
 *
 * Оба ответа равноправны: «Не скажу» и «Не надо» — такие же кнопки, а не мелкий
 * текст снизу. Вопрос, от которого неудобно отказаться, перестаёт быть вопросом.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskBubble(item: FeedItem.Ask, onYes: () -> Unit, onNo: () -> Unit) {
    val colors = KcalTheme.colors
    val (question, yes, no) = when (item.kind) {
        AskKind.NAME -> Triple("Кстати, как тебя зовут? Буду знать, к кому обращаться.", "Сказать", "Не скажу")
        AskKind.WRITE -> Triple(
            "Можно мы будем иногда писать тебе сами? Если пропадёшь или забудешь поесть. " +
                "Не чаще пары раз в день — честно.",
            "Пишите",
            "Не надо",
        )
    }

    IncomingBubble(item) {
        BubbleText(question)
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            KcalChip(onClick = onYes, background = colors.bubble) {
                Text(yes, style = KcalTheme.type.chip, color = colors.onBubble)
            }
            KcalChip(onClick = onNo, background = colors.chip) {
                Text(no, style = KcalTheme.type.chip, color = colors.text)
            }
        }
    }
}

/**
 * Очередь отправки: что скопилось и кнопка.
 *
 * Воркеры уносят это сами, когда есть сеть, — но молча, и человек не знает,
 * ушло ли. Кнопка делает то же самое сейчас и отвечает репликой.
 */
@Composable
fun OutboxBubble(item: FeedItem.Outbox, onSend: () -> Unit) {
    val colors = KcalTheme.colors

    IncomingBubble(item) {
        BubbleText("Не отправлено на сервер: ${describeOutbox(item.count.scans, item.count.products)}")
        KcalChip(
            onClick = if (item.sending) null else onSend,
            background = if (item.sending) colors.chip else colors.bubble,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(
                if (item.sending) "Отправляю…" else "Отправить",
                style = KcalTheme.type.chip,
                color = if (item.sending) colors.text3 else colors.onBubble,
            )
        }
    }
}

/** Предсказанный продукт: тап добавляет его целиком, вместе с типичной порцией. */
@Composable
fun CandidateChip(candidate: FoodCandidate, onClick: () -> Unit) {
    val colors = KcalTheme.colors
    val grams = candidate.servingG ?: 0
    val macro = dominantMacro(candidate.nutriments)

    KcalChip(onClick = onClick, background = colors.chip) {
        MacroDot(macro.colors().fill)
        Text(
            candidate.displayName,
            style = KcalTheme.type.chip,
            color = colors.text,
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
