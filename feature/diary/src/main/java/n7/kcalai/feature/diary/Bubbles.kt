package n7.kcalai.feature.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import n7.kcalai.ui.colors
import n7.kcalai.ui.letter
import n7.kcalai.ui.KcalShapes
import n7.kcalai.ui.KcalTheme

/** Доля ширины, которую пузырёк не переступает: лента должна читаться как диалог. */
private const val BUBBLE_WIDTH = 0.88f

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
                .clip(
                    RoundedCornerShape(
                        topStart = KcalShapes.bubble,
                        topEnd = KcalShapes.bubble,
                        bottomEnd = KcalShapes.bubbleTail,
                        bottomStart = KcalShapes.bubble,
                    )
                )
                .background(colors.bubble)
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    mealName(item.meal),
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
    modifier: Modifier = Modifier,
    time: String? = null,
    onDismiss: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = KcalTheme.colors

    Box(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth(BUBBLE_WIDTH)
                .wrapContentWidth(Alignment.Start)
                .clip(
                    RoundedCornerShape(
                        topStart = KcalShapes.bubble,
                        topEnd = KcalShapes.bubble,
                        bottomEnd = KcalShapes.bubble,
                        bottomStart = KcalShapes.bubbleTail,
                    )
                )
                .background(colors.surface)
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row {
                Column(Modifier.weight(1f)) { content() }

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

            if (time != null) {
                Text(
                    time,
                    style = KcalTheme.type.time,
                    color = colors.text3,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** Фраза в реплике приложения. */
@Composable
private fun BubbleText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = KcalTheme.type.body, color = KcalTheme.colors.text, modifier = modifier)
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
fun SummaryBubble(item: FeedItem.Summary) {
    IncomingBubble {
        BubbleTitle(item.greeting)
        item.weekLine?.let { BubbleText(it, Modifier.padding(top = 7.dp)) }
        item.outline?.let { outline ->
            BubbleText(outlineSentence(outline), Modifier.padding(top = 7.dp))
            DayOutlineBar(outline, Modifier.padding(top = 10.dp))
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
 * Формулировка намеренно нейтральная. «Вы не обедали» — упрёк и повод удалить
 * приложение; «Обед пропустили?» — вопрос, на который есть три ответа в один тап
 * и четвёртый, закрывающий его до завтра.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GapBubble(
    item: FeedItem.Gap,
    onPick: (FoodCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    val gap: MealGap = item.gap

    IncomingBubble(onDismiss = onDismiss) {
        BubbleTitle("${mealName(gap.meal)} пропустили?")
        BubbleText(
            "Обычно вы ${mealVerb(gap.meal)} около ${formatHour(gap.typicalHour)}, " +
                "а сегодня ${mealNameGenitive(gap.meal)} в дневнике нет. " +
                "Если ели — добавьте одним тапом.",
            Modifier.padding(top = 7.dp),
        )
        FlowRow(
            modifier = Modifier.padding(top = 10.dp),
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

    IncomingBubble {
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
                append(". Из вашего обычного подходит:")
            }
        )
        FlowRow(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            plan.options.forEach { option ->
                OptionChip(option) { onPick(option) }
            }
        }
    }
}

/** Съедено больше цели. Констатация и обещание, а не оценка поступка. */
@Composable
fun OverBubble(item: FeedItem.Over) {
    IncomingBubble {
        BubbleText(
            "Цель на сегодня закрыта с перебором в ${item.overKcal} ккал. " +
                "Ничего страшного — завтра это учтётся в раскладке дня."
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
    IncomingBubble {
        BubbleText(
            "«${item.query}» не нашлась. Попробуйте назвать блюдо иначе, " +
                "отсканируйте штрих-код или сфотографируйте этикетку — добавим в базу."
        )
    }
}

/** Вес записан. Подтверждение действия, у которого больше нет своего экрана. */
@Composable
fun WeightBubble(item: FeedItem.WeightLogged) {
    IncomingBubble(time = item.time) {
        BubbleText("Записал ${formatKg(item.grams)}")
    }
}

/** Ответ на действие — «Отправлено: 2 сессии». */
@Composable
fun ReplyBubble(item: FeedItem.Reply) {
    IncomingBubble(time = item.time) {
        BubbleText(item.text)
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

    IncomingBubble {
        BubbleText("Не отправлено на сервер: ${describeOutbox(item.count.scans, item.count.products)}")
        KcalChip(
            onClick = if (item.sending) null else onSend,
            background = if (item.sending) colors.chip else colors.bubble,
            modifier = Modifier.padding(top = 10.dp),
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

/** Разделитель дня. Один на всю ленту: прошлых дней в ней пока нет. */
@Composable
fun DaySeparator() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CapsLabel("сегодня", color = KcalTheme.colors.text2)
    }
}
