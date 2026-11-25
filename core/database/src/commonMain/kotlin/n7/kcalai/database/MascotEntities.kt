package n7.kcalai.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import n7.kcalai.model.DEFAULT_HEIGHT_CM
import n7.kcalai.model.DEFAULT_STEPS_GOAL
import n7.kcalai.model.Mascot
import n7.kcalai.model.PageGrace
import n7.kcalai.model.Sex

/** Куда ушло сообщение персонажа. */
enum class Delivery {
    /** Ответ в ленте: на имя, на согласие, на «Записать» из шторки. */
    FEED,

    /** Персонаж написал первым — в шторку. Только такие считаются в дневной бюджет. */
    NOTIFICATION,
}

/** Можно ли персонажам писать первыми. Спрашивают в ленте, на любой версии Android. */
enum class WriteConsent { UNASKED, ALLOWED, DECLINED }

/** Что человек ответил на вопрос персонажа: «Сказать» и «Пишите» — [YES], «Не скажу» и «Не надо» — [NO]. */
enum class Choice { YES, NO }

/**
 * Что персонаж сказал человеку — первым или в ответ.
 *
 * Хранится, а не живёт в памяти, по трём причинам. Сообщение из шторки обязано
 * стоять в переписке дня, когда человек откроет приложение. Варианты, из которых
 * оно сложено ([lineIds]), — память «не повторяться». А время и [openedAt] —
 * бюджет и подстройка частоты: на проигнорированные сообщения персонажи пишут реже.
 *
 * [topic] — имя темы строкой: темы живут в мозге персонажей, а база о нём не знает.
 */
@Entity(tableName = "mascot_message", indices = [Index("dateEpochDay"), Index("createdAt")])
data class MascotMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mascot: Mascot,
    val topic: String,
    val text: String,
    /** id вариантов через запятую. */
    val lineIds: String,
    val delivery: Delivery,
    /** День переписки — в местной зоне человека, как у записей дневника. */
    val dateEpochDay: Long,
    val createdAt: Long,
    /** Когда человек его увидел: открыл из шторки или просто вернулся в приложение. */
    val openedAt: Long? = null,
    /**
     * Ответ, если сообщение — вопрос; `null` — не вопрос или ответа ещё нет.
     *
     * Ответ ложится в сам вопрос, а не стирает его: лента — переписка, и отвеченный
     * вопрос остаётся в ней с выбранной кнопкой, погасшей вместе с остальными.
     */
    val choice: Choice? = null,
    /**
     * Когда человек скрыл его корзинкой; `null` — на месте. Скрытое не стирается: по нему персонажи
     * помнят, что уже говорили и о чём спрашивали, — иначе спросили бы снова.
     */
    val hiddenAt: Long? = null,
)

/**
 * Что персонажи знают о человеке сверх дневника. Одна строка.
 *
 * Имя называет сам человек, пол — из помощника цели. Без них персонажи
 * обходятся: не обращаются по имени и не говорят «ты ел».
 */
@Entity(tableName = "mascot_profile")
data class MascotProfileEntity(
    @PrimaryKey val id: Int = 0,
    val name: String? = null,
    /** Спрашивали ли имя. «Не скажу» — тоже ответ, второй раз не спрашивают. */
    val nameAsked: Boolean = false,
    val sex: Sex? = null,
    val consent: WriteConsent = WriteConsent.UNASKED,
    /** Когда человек ответил про сообщения: «Не надо» переспрашивается через месяц. */
    val consentAt: Long? = null,
    /** Когда человек последний раз открывал приложение: по нему встречают и не мешают. */
    val lastOpenAt: Long? = null,
    /** Сколько держать страницу дневника в браузере, когда телефон убран. */
    @ColumnInfo(defaultValue = "MIN_5")
    val pageGrace: PageGrace = PageGrace.MIN_5,
    /** Цель шагов на день; `null` — не задавали, см. [stepsGoalOrDefault]. */
    val stepsGoal: Int? = null,
    /** Рост, см: по нему меряется шаг на дорожке; `null` — не называли, см. [heightOrDefault]. */
    val heightCm: Int? = null,
)

/** Цель шагов на день — заданная или общая, пока её не задали. */
val MascotProfileEntity.stepsGoalOrDefault: Int get() = stepsGoal ?: DEFAULT_STEPS_GOAL

/** Рост для длины шага — названный или средний, пока его не назвали. */
val MascotProfileEntity.heightOrDefault: Int get() = heightCm ?: DEFAULT_HEIGHT_CM
