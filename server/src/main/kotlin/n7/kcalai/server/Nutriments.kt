package n7.kcalai.server

/** КБЖУ на 100 г: ккал целые, макросы в сотых грамма — как в контракте и на клиенте. */
data class Nutriments(val kcal100: Int, val prot100: Int, val fat100: Int, val carb100: Int) {

    /**
     * Те же пределы, что у `NutrimentValidator` на клиенте. Разъедутся — сервер примет то, что клиент отверг.
     *
     * Проверки на каждый макрос по отдельности здесь нет намеренно: при
     * неотрицательных значениях «один больше ста грамм» всегда означает и «сумма
     * больше ста грамм», то есть отсекается строкой ниже. Клиент такую проверку
     * держит, потому что ему нужно сказать человеку, в каком поле лишний разряд;
     * серверу достаточно знать, что вклад негоден.
     */
    fun isPlausible(): Boolean =
        kcal100 in 0..KCAL_MAX &&
            prot100 >= 0 && fat100 >= 0 && carb100 >= 0 &&
            prot100 + fat100 + carb100 <= CENTIGRAMS_MAX

    private companion object {
        const val KCAL_MAX = 900
        const val CENTIGRAMS_MAX = 100 * 100
    }
}

data class Product(
    val gtin: String,
    val name: String,
    val brand: String?,
    val nutriments: Nutriments,
    val servingG: Int?,
    /** Клетчатка, сотые грамма на 100 г; `null` — неизвестна. */
    val fiber100: Int? = null,
)

data class Contribution(
    val gtin: String,
    val name: String,
    val nutriments: Nutriments,
    val servingG: Int?,
    val createdAt: Long,
    /** Клетчатка, сотые грамма на 100 г; `null` — не указана. */
    val fiber100: Int? = null,
)

data class Vote(val contribution: Contribution, val receivedAt: Long, val pending: Boolean)

data class QueueItem(val gtin: String, val name: String, val votes: Int, val lastReceivedAt: Long)

enum class Verdict { PUBLISHED, REJECTED }

data class Decision(val verdict: Verdict, val decidedAt: Long)
