package n7.kcalai.mascot

import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.Mascot

/** Что случилось с персонажами на экране — и на что они могут ответить. */
sealed interface MascotEvent {
    /** Тык по персонажу. [count] — какой подряд. */
    data class Poked(val mascot: Mascot, val count: Int) : MascotEvent

    /** Двойной тап — фирменный трюк. */
    data class Trick(val mascot: Mascot) : MascotEvent

    /** Подняли и тащат. */
    data class Held(val mascot: Mascot) : MascotEvent

    /** Отпустили. [thrown] — с размаху. */
    data class Dropped(val mascot: Mascot, val thrown: Boolean) : MascotEvent

    /** Погладили. */
    data class Petted(val mascot: Mascot) : MascotEvent

    /** Разбудили. [night] — ночью, а не от скуки задремал. */
    data class Woken(val mascot: Mascot, val night: Boolean) : MascotEvent

    /** Человек записал еду. */
    data class Ate(val entry: DiaryEntryEntity) : MascotEvent

    /** Калории дня легли точно в цель. */
    data object GoalReached : MascotEvent

    /** Открыл приложение из уведомления. */
    data class Opened(val messageId: Long) : MascotEvent

    /** Тряхнули телефон. */
    data object Shaken : MascotEvent
}

/** Как сцена должна отыграть событие. */
enum class CueKind {
    /** Хозяин записанной еды подпрыгивает. */
    PROUD,

    /** Все трое празднуют по очереди. */
    CELEBRATE,

    /** Встречают вернувшегося. */
    GREET,

    /** Автор открытого сообщения радуется. */
    OPENED,

    /** У всех кружится голова. */
    DIZZY,
}

/** @param mascot кто отыгрывает; `null` — все трое. */
data class Cue(val kind: CueKind, val mascot: Mascot?)

/** Облачко над головой. */
data class Speech(val mascot: Mascot, val text: String)

/** Ответ персонажей на событие: движение, слова или то и другое. */
data class Reaction(val cue: Cue?, val speech: Speech?)
