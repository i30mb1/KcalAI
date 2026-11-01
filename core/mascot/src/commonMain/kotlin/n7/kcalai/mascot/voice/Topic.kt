package n7.kcalai.mascot.voice

/**
 * Где звучит реплика — и сколько в ней может быть букв.
 *
 * Уведомление обрезается шторкой, облачко над головой должно читаться на бегу,
 * ответ в ленте — обычный пузырёк.
 */
enum class TopicKind(val maxLength: Int) {
    /** Персонаж пишет первым — в шторку. */
    NOTE(180),

    /** Ответ на действие человека — в ленту или в ту же переписку в шторке. */
    ANSWER(140),

    /** Облачко над головой: живёт секунды и в ленту не попадает. */
    SPEECH(70),
}

/** О чём персонаж говорит. Кто из троих на какую тему — в `Voices.cast`. */
enum class Topic(val kind: TopicKind) {
    /** Пропал на 2, 4, 7, 14 дней. */
    COMEBACK(TopicKind.NOTE),

    /** Обычный приём пищи сегодня не записан. */
    MISSED_MEAL(TopicKind.NOTE),

    /** С последней еды три часа и больше. */
    LONG_GAP(TopicKind.NOTE),

    /** Вчера записи были, сегодня пусто. */
    EMPTY_DAY(TopicKind.NOTE),

    /** Серия дней с записями под угрозой. */
    STREAK_KEEP(TopicKind.NOTE),

    /** Вечер, а белок не добран. */
    PROTEIN_LEFT(TopicKind.NOTE),

    /** Вчера калории легли в цель. */
    YESTERDAY_WIN(TopicKind.NOTE),

    /** Человек нажал «Записать» прямо в шторке. */
    LOGGED(TopicKind.ANSWER),

    /** Человек назвал имя. */
    NAME_THANKS(TopicKind.ANSWER),

    /** Разрешил писать, и телефон разрешил тоже. */
    WRITE_YES(TopicKind.ANSWER),

    /** Попросил не писать. */
    WRITE_NO(TopicKind.ANSWER),

    /** Разрешил, а телефон — нет. */
    WRITE_DENIED(TopicKind.ANSWER),

    /** Третий тык подряд. */
    POKE(TopicKind.SPEECH),

    /** Шестой тык подряд. */
    POKE_ANNOYED(TopicKind.SPEECH),

    /** Двойной тап — фирменный трюк. */
    TRICK(TopicKind.SPEECH),

    /** Подняли и тащат. */
    HELD(TopicKind.SPEECH),

    /** Отпустили, и он вернулся на место. */
    DROPPED(TopicKind.SPEECH),

    /** Бросили с размаху. */
    THROWN(TopicKind.SPEECH),

    /** Погладили. */
    PETTED(TopicKind.SPEECH),

    /** Разбудили ночью. */
    WOKEN_NIGHT(TopicKind.SPEECH),

    /** Разбудили днём: задремал от скуки. */
    WOKEN_IDLE(TopicKind.SPEECH),

    /** Человек вернулся через несколько часов. */
    GREET_HOURS(TopicKind.SPEECH),

    /** Человек вернулся через день и больше. */
    GREET_DAYS(TopicKind.SPEECH),

    /** Открыл приложение из уведомления этого персонажа. */
    OPENED(TopicKind.SPEECH),

    /** Первая запись за день. */
    MEAL_FIRST(TopicKind.SPEECH),

    /** Такой еды в истории ещё не было. */
    MEAL_NEW(TopicKind.SPEECH),

    /** Та же еда третий день подряд и дольше. */
    MEAL_REPEAT(TopicKind.SPEECH),

    /** Еда глубокой ночью. */
    MEAL_LATE(TopicKind.SPEECH),

    /** Записали еду «его» макроса. */
    MEAL_OWNER(TopicKind.SPEECH),

    /** Телефон тряхнули. */
    SHAKEN(TopicKind.SPEECH),

    /** Калории дня легли точно в цель. */
    GOAL_REACHED(TopicKind.SPEECH),
}
