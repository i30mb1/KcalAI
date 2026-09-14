package n7.kcalai.server

/** КБЖУ на 100 г: ккал целые, макросы в сотых грамма — как в контракте и на клиенте. */
data class Nutriments(val kcal100: Int, val prot100: Int, val fat100: Int, val carb100: Int) {

    /** Те же пределы, что у `NutrimentValidator` на клиенте. Разъедутся — сервер примет то, что клиент отверг. */
    fun isPlausible(): Boolean =
        kcal100 in 0..KCAL_MAX &&
            prot100 in 0..CENTIGRAMS_MAX && fat100 in 0..CENTIGRAMS_MAX && carb100 in 0..CENTIGRAMS_MAX &&
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
)

data class Contribution(
    val gtin: String,
    val name: String,
    val nutriments: Nutriments,
    val servingG: Int?,
    val createdAt: Long,
)
