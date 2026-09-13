package n7.kcalai.personal

import java.time.ZoneOffset
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodRef
import n7.kcalai.model.MealType
import n7.kcalai.model.serialize

/**
 * UTC, а не системная зона: час записи вычисляется из `createdAt`, и тест,
 * зависящий от зоны машины, проходил бы в Москве и падал в Лондоне.
 */
internal val TEST_ZONE = ZoneOffset.UTC

internal const val TODAY = 20_000L

internal fun entry(
    id: Long,
    day: Long,
    hour: Int,
    meal: MealType,
    name: String,
    genericId: Long,
    grams: Int = 100,
    kcal100: Int = 100,
    prot100: Int = 1000,
): DiaryEntryEntity = DiaryEntryEntity(
    id = id,
    dateEpochDay = day,
    meal = meal,
    displayName = name,
    grams = grams,
    kcal100 = kcal100,
    prot100 = prot100,
    fat100 = 500,
    carb100 = 1000,
    source = EntrySource.TEXT,
    foodRef = FoodRef.Generic(genericId).serialize(),
    createdAt = (day * SECONDS_PER_DAY + hour * SECONDS_PER_HOUR) * 1000L,
)

internal fun genericKey(id: Long): String = FoodRef.Generic(id).serialize()

internal fun historyOf(entries: List<DiaryEntryEntity>, today: Long = TODAY): FoodHistory =
    FoodHistory.from(entries, today, TEST_ZONE)

internal fun contextAt(
    hour: Int,
    meal: MealType,
    prevRefKey: String? = null,
    today: Long = TODAY,
): PersonalContext = PersonalContext(
    dateEpochDay = today,
    hourOfDay = hour,
    meal = meal,
    prevRefKey = prevRefKey,
)

private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_HOUR = 3_600L
