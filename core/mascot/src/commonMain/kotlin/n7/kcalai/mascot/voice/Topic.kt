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

    /** Докачивается умный поиск: пока ищем только по словам. */
    SEARCH_LOADING(TopicKind.ANSWER),

    /** Умный поиск большой и ждёт Wi-Fi. */
    SEARCH_WAITING_WIFI(TopicKind.ANSWER),

    /** Умный поиск не докачался. */
    SEARCH_FAILED(TopicKind.ANSWER),

    /** Умный поиск на месте: опечатки и разговорные названия теперь находятся. */
    SEARCH_READY(TopicKind.ANSWER),

    /** Докачивается нейросеть. */
    ASSISTANT_LOADING(TopicKind.ANSWER),

    /** Нейросеть не докачалась. */
    ASSISTANT_FAILED(TopicKind.ANSWER),

    /** Нейросеть на месте: разбор фраз и вопросы к дневнику. */
    ASSISTANT_READY(TopicKind.ANSWER),

    /** Приложение совсем новое: что не так или чего не хватает — пиши разработчику на почту. */
    APP_NEW(TopicKind.ANSWER),

    /** Дневник открывается и с компьютера: адрес страницы на телефоне — один раз сам, дальше по просьбе. */
    WEB_ADDRESS(TopicKind.ANSWER),
    /** «Нет» на предложение вести дневник с компьютера: без адреса, и как к нему вернуться. */
    WEB_LATER(TopicKind.ANSWER),

    /** Адрес страницы спросили, а его нет: телефон не в Wi-Fi. */
    WEB_NO_WIFI(TopicKind.ANSWER),

    /** Прогулка по дорожке кончилась: сколько прошли и сколько ещё до цели шагов. */
    WALK_DONE(TopicKind.ANSWER),

    /** Прогулка добрала цель шагов на день. */
    WALK_GOAL(TopicKind.ANSWER),

    /** Цель шагов на день взята без итога прогулки — ходьбой с телефоном. Раз в день. */
    STEPS_GOAL(TopicKind.ANSWER),
    /** Круглый день серии с записями — третий, седьмой, четырнадцатый… Сообщением в ленте, раз в день. */
    STREAK_DAY(TopicKind.ANSWER),
    /** Итог прошлой недели: сколько дней с записями, сколько в цель, что ели чаще всего. Раз в неделю. */
    WEEK_SUMMARY(TopicKind.ANSWER),

    /** Дорожку выбрали — телефон подключится к ней сам, когда её включат. */
    TREADMILL_YES(TopicKind.ANSWER),

    /** «Не надо» на вопрос про дорожку. */
    TREADMILL_NO(TopicKind.ANSWER),

    /** Дорожку не выбрали или телефон не дал Bluetooth. */
    TREADMILL_FAILED(TopicKind.ANSWER),

    /** Шагомер телефона разрешили читать — его шаги идут в дневник вместе с дорожкой. */
    STEPS_YES(TopicKind.ANSWER),

    /** «Не надо» на вопрос про шаги телефона. */
    STEPS_NO(TopicKind.ANSWER),

    /** Телефон не дал шагомер или его нет. */
    STEPS_FAILED(TopicKind.ANSWER),

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
