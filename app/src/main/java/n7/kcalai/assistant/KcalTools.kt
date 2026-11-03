@file:OptIn(ExperimentalAppFunctionsApi::class)

package n7.kcalai.assistant

import androidx.appfunctions.AppFunctionAccessLevel
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.appfunctions.ExperimentalAppFunctionsApi
import androidx.appfunctions.metadata.AppFunctionMetadata
import n7.kcalai.KcalApp

/**
 * Инструменты нейросети — AppFunctions приложения.
 *
 * Описания из KDoc уходят модели как есть: по ним она решает, что звать и с чем.
 * Все — только для своего агента (`ACCESS_LEVEL_SELF`): системные ассистенты
 * и чужие приложения дневник через них не читают.
 *
 * Логика — в [DiaryTools], здесь только объявления: так её можно проверить
 * тестами без платформы, которой AppFunctions нужны.
 */
@AppFunctionServiceEntryPoint(
    serviceName = "KcalAppFunctionService",
    appFunctionXmlFileName = "kcal_app_functions",
)
abstract class KcalTools : AppFunctionService() {

    private val tools: DiaryTools get() = (application as KcalApp).container.diaryTools

    /**
     * Searches the user's local food database. Returns lines "foodId | name | nutrition per 100 g | portion".
     *
     * @param query Short Russian food name in nominative case, 1-3 words, for example "борщ" or "хлеб ржаной"
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    @AppFunctionAccessLevel(level = AppFunctionMetadata.ACCESS_LEVEL_SELF, isCompatEnforcementEnabled = true)
    suspend fun search_food(query: String): String = tools.searchFood(query)

    /**
     * Proposes one diary entry to the user, who confirms it with a tap. Does not write anything by itself.
     *
     * @param foodId The foodId exactly as returned by search_food, for example "generic:12"
     * @param grams How many grams were eaten
     * @param phrase The words of the user's text that describe this dish
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    @AppFunctionAccessLevel(level = AppFunctionMetadata.ACCESS_LEVEL_SELF, isCompatEnforcementEnabled = true)
    suspend fun propose_food(foodId: String, grams: Int, phrase: String): String =
        if (tools.proposal(DiaryTools.PROPOSE, mapOf("foodId" to foodId, "grams" to grams)) != null) {
            "Proposed to the user."
        } else {
            "Rejected: use a foodId returned by search_food and grams between 1 and 1500."
        }

    /**
     * Diary totals for the last days, newest first: calories and macros eaten, the goal,
     * and what is left of today's goal.
     *
     * @param days How many days back to include, from 1 to 14
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    @AppFunctionAccessLevel(level = AppFunctionMetadata.ACCESS_LEVEL_SELF, isCompatEnforcementEnabled = true)
    suspend fun diary_days(days: Int): String = tools.diaryDays(days)

    /**
     * Everything logged on one day: meal, food, grams and calories of each entry.
     *
     * @param daysAgo 0 for today, 1 for yesterday, up to 30
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    @AppFunctionAccessLevel(level = AppFunctionMetadata.ACCESS_LEVEL_SELF, isCompatEnforcementEnabled = true)
    suspend fun day_entries(daysAgo: Int): String = tools.dayEntries(daysAgo)
}
