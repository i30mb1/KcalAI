package n7.kcalai.feature.scanner

import n7.kcalai.model.Mascot

/** С чем персонаж сидит над камерой. */
internal enum class MascotMood {
    /** Ждёт своё число с камеры — рука поднята. */
    ASKING,

    /** Своё получил, назвал и ждёт остальных. */
    WAITING,

    /** Камера не справилась — просит вписать руками. */
    TYPING,

    /** Всё заполнено и сходится — просят проверить и сохранить. */
    DONE,
}

internal data class MascotLine(val mascot: Mascot, val mood: MascotMood, val text: String)

/**
 * Что говорят трое над камерой, пока заполняется карточка.
 *
 * Каждый отвечает за свой макрос — тот же, что в его плитке дневника: белки —
 * Яйцо, жиры — Масло, углеводы — Зёрнышко. Получил своё — называет число и ждёт
 * остальных. У калорий и названия своего персонажа нет; о них, когда больше
 * не о чем, говорит Яйцо — староста.
 *
 * Голоса — из `specs/2026-11-01-living-mascots-design.md`: к человеку на «ты»,
 * Яйцо собранное и докладывает, Масло мягкое, Зёрнышко торопится. Ни одной
 * выдуманной цифры: числа в репликах — ровно те, что стоят в полях.
 */
internal fun ScanState.mascotLines(): List<MascotLine> {
    val complete = canSave
    return listOf(
        eggLine(complete),
        macroLine(Mascot.BUTTER, fat, BUTTER, complete),
        macroLine(Mascot.GRAIN, carb, GRAIN, complete),
    )
}

private fun ScanState.macroLine(mascot: Mascot, field: ScanField, voice: Voice, complete: Boolean) = when {
    complete -> MascotLine(mascot, MascotMood.DONE, voice.done)
    !isMissing(field) -> MascotLine(mascot, MascotMood.WAITING, voice.waiting(field.text.trimEnd(',')))
    manual -> MascotLine(mascot, MascotMood.TYPING, voice.typing)
    else -> MascotLine(mascot, MascotMood.ASKING, voice.asking)
}

/**
 * Яйцо говорит о белках, пока они не прочитаны. Потом — о том, что осталось
 * без хозяина: калории, название, не сошедшиеся цифры.
 */
private fun ScanState.eggLine(complete: Boolean): MascotLine {
    val egg = Mascot.EGG
    val macrosRead = listOf(prot, fat, carb).none(::isMissing)
    return when {
        complete -> MascotLine(egg, MascotMood.DONE, EGG.done)
        // Пока не прочитано ничего — сначала о том, почему карточка вообще открылась.
        isMissing(prot) && fromMiss && filledFields == 0 && !manual ->
            MascotLine(egg, MascotMood.ASKING, "Такого ещё нет! Покажи, пожалуйста, белки")
        !macrosRead -> macroLine(egg, prot, EGG, complete = false)
        isMissing(kcal) && manual -> MascotLine(egg, MascotMood.TYPING, "Осталось вписать калории")
        isMissing(kcal) -> MascotLine(egg, MascotMood.ASKING, "Осталось: калории. Покажи эту строку")
        name.text.isBlank() -> MascotLine(egg, MascotMood.ASKING, "Как называется продукт? Впиши сверху")
        else -> MascotLine(egg, MascotMood.ASKING, "Цифры не сходятся. Проверь, пожалуйста")
    }
}

/** Четыре реплики персонажа о своём макросе. */
private class Voice(
    val asking: String,
    val waiting: (String) -> String,
    val typing: String,
    val done: String,
)

private val EGG = Voice(
    asking = "Покажи, пожалуйста, белки",
    waiting = { grams -> "Белки $grams г. Зафиксировал, ждём" },
    typing = "Белков не вижу. Впиши, пожалуйста",
    done = "Всё на месте. Проверь и сохраняй",
)

private val BUTTER = Voice(
    asking = "А я тихонько жду жиры",
    waiting = { grams -> "Жиры $grams г. Тепло. Жду" },
    typing = "Не разгляжу… Впишешь жиры?",
    done = "Как хорошо всё сошлось",
)

private val GRAIN = Voice(
    asking = "Углеводы, пожалуйста! Погнали!",
    waiting = { grams -> "Углеводы $grams г! Ждём!" },
    typing = "Не видно! Впиши углеводы, а?",
    done = "Погнали сохранять!",
)

/**
 * Строка-инструкция в панели — или `null`, когда говорить нечего.
 *
 * Персонажи говорят, чего не хватает; подсказка — как это дать: навести камеру
 * или нажать на поле. Когда вписывают руками — про строку, которой на пачке нет:
 * у сахара строк «Белки» и «Жиры» нет вовсе, и человек ищет, что вписать в поле,
 * которого на пачке не найти.
 *
 * @param camera доступ к камере есть
 * @param answered человек ответил на запрос доступа
 * @param ocr распознавание на этом устройстве работает
 * @param manual числа вписывают руками
 * @param numbersMissing каких-то чисел с этикетки ещё нет
 * @param nameMissing названия ещё нет
 */
internal fun panelHint(
    camera: Boolean,
    answered: Boolean,
    ocr: Boolean,
    manual: Boolean,
    numbersMissing: Boolean,
    nameMissing: Boolean,
): String? {
    // Числа вписаны — про камеру и про ноль говорить поздно: осталось только название.
    if (!numbersMissing) return if (nameMissing) "Назовите продукт — без названия его потом не найти" else null
    return when {
        !camera && answered -> "Камеры нет — впишите числа с пачки"
        !camera -> "Запрашиваем доступ к камере"
        !ocr -> "Распознавание недоступно — впишите числа с пачки"
        manual -> "Если строки на пачке нет — впишите 0"
        else -> "Держите таблицу КБЖУ в рамке — или нажмите на поле и впишите сами"
    }
}
