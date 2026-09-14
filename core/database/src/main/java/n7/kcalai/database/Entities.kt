package n7.kcalai.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType
import n7.kcalai.model.ProductOrigin

/**
 * Запись дневника.
 *
 * КБЖУ хранится снимком, а не ссылкой: обновление справочника не должно
 * задним числом переписывать историю.
 */
@Entity(tableName = "diary_entry", indices = [Index("dateEpochDay")])
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val meal: MealType,
    val displayName: String,
    val grams: Int,
    val kcal100: Int,
    val prot100: Int,
    val fat100: Int,
    val carb100: Int,
    val source: EntrySource,
    /** Только для «повторить». Может протухнуть после обновления справочника — это нормально. */
    val foodRef: String?,
    val createdAt: Long,
)

/**
 * Продукт, заведённый пользователем после промаха сканера.
 *
 * [servingG] — та порция, которую человек указал на экране съёмки. Хранится,
 * потому что иначе теряется при первом же повторном скане: продукт находится
 * по коду мгновенно, но снова с подставленной сотней грамм, и указанное однажды
 * приходится вводить заново каждый раз.
 */
@Entity(tableName = "user_food", indices = [Index(value = ["barcode"], unique = true)])
data class UserFoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val barcode: String?,
    val name: String,
    val kcal100: Int,
    val prot100: Int,
    val fat100: Int,
    val carb100: Int,
    val servingG: Int? = null,
    val createdAt: Long,
)

/**
 * Ответ сети, сохранённый до следующего раза.
 *
 * Отдельная таблица, а не строки в `user_food`, — и причина не в удобстве.
 * [ProductOrigin] помечает происхождение, а разделение хранилищ гарантирует, что
 * пришедшее из Open Food Facts физически не может попасть в очередь отправки:
 * [ContributionEntity] собирается из `user_food`, куда сеть не пишет никогда.
 *
 * Побочная польза обычная: повторный скан того же товара работает в самолёте.
 */
@Entity(tableName = "cached_product")
data class CachedProductEntity(
    @PrimaryKey val gtin: String,
    val name: String,
    val brand: String?,
    val kcal100: Int,
    val prot100: Int,
    val fat100: Int,
    val carb100: Int,
    val servingG: Int?,
    val origin: ProductOrigin,
    val fetchedAt: Long,
)

/**
 * Продукт, заведённый руками, в очереди на отправку.
 *
 * Дубликат `user_food` по содержимому, и это намеренно: человек вправе переименовать
 * или удалить свой продукт, а уже отправленный вклад от этого меняться не должен.
 * Очередь — журнал того, что мы обещали отдать, а не проекция текущего состояния.
 *
 * [sentAt] `null` означает «ещё не ушло». Строки не удаляются после отправки:
 * повторная отправка того же GTIN стоит дешевле, чем потерянный вклад.
 */
@Entity(tableName = "contribution", indices = [Index("sentAt")])
data class ContributionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gtin: String,
    val name: String,
    val kcal100: Int,
    val prot100: Int,
    val fat100: Int,
    val carb100: Int,
    val servingG: Int?,
    val createdAt: Long,
    val sentAt: Long? = null,
)

/**
 * Цель по КБЖУ, действующая с даты [fromDateEpochDay] и до следующей цели.
 * Версионируется, чтобы смена цели не переписывала прогресс прошлых дней.
 */
@Entity(tableName = "daily_goal")
data class DailyGoalEntity(
    @PrimaryKey val fromDateEpochDay: Long,
    val kcal: Int,
    val prot: Int,
    val fat: Int,
    val carb: Int,
)

/** Вес тела за день. Ключ — дата: взвешиваний за сутки может быть много, запись одна. */
@Entity(tableName = "body_metric")
data class BodyMetricEntity(
    @PrimaryKey val dateEpochDay: Long,
    val weightGrams: Int,
    val createdAt: Long,
)

/**
 * Что человеку показали и что он из этого выбрал.
 *
 * Экран подтверждения — единственный источник размеченных данных в приложении:
 * выбранный вариант это положительный пример, показанные выше него и отвергнутые —
 * отрицательные. Без журнала сигнал теряется безвозвратно, поэтому пишется он
 * всегда, независимо от того, обучается ли кто-нибудь на нём прямо сейчас.
 */
@Entity(tableName = "suggestion_event", indices = [Index("createdAt")])
data class SuggestionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val hourOfDay: Int,
    val meal: MealType,
    /** Нормализованный текст запроса — тот, что уходил в поиск. */
    val query: String,
    /** Ссылки показанных кандидатов через `|`. Порядок — тот, в котором их видел человек. */
    val shownRefs: String,
    val pickedRef: String,
    /** Позиция выбранного в списке показанных, с нуля. */
    val pickedIndex: Int,
)

/**
 * Сколько грамм у ЭТОГО человека весит одна единица ЭТОГО продукта.
 *
 * Общая таблица `portion_unit` знает, что горсть орехов — 30 г. Но «тарелка супа»
 * у разных людей отличается вдвое, и спорить с этим бесполезно: правильный ответ
 * тот, который человек подтверждает раз за разом.
 */
@Entity(tableName = "portion_sample", indices = [Index("refKey")])
data class PortionSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [n7.kcalai.model.serialize] от ссылки на продукт. */
    val refKey: String,
    /** Каноническая единица: «шт», «ст.л.», «порция». */
    val unit: String,
    val gramsPerUnit: Int,
    val createdAt: Long,
)

/** Один вес ре-ранкера. Их восемь — отдельная таблица честнее, чем строка с разделителями. */
@Entity(tableName = "ranker_weight")
data class RankerWeightEntity(
    @PrimaryKey val name: String,
    val value: Double,
)

/** До какого события ре-ранкер уже обучен. Обучение инкрементально и не переигрывает журнал. */
@Entity(tableName = "ranker_state")
data class RankerStateEntity(
    @PrimaryKey val id: Int = 0,
    val lastEventId: Long,
    val trainedEvents: Int,
)

/**
 * «Я пропустил этот приём пищи» — ответ на баннер.
 *
 * Хранится, а не держится в памяти: иначе баннер про несъеденный обед возвращается
 * при каждом перезапуске приложения и из напоминания превращается в упрёк.
 */
@Entity(tableName = "meal_gap_dismiss", primaryKeys = ["dateEpochDay", "meal"])
data class MealGapDismissEntity(
    val dateEpochDay: Long,
    val meal: MealType,
)
