package n7.kcalai.server

import java.io.File
import java.sql.DriverManager
import org.sqlite.SQLiteConfig

/**
 * Товар из справочника, который раздаётся телефонам, — для сверки с голосами в панели.
 * Файл подменяет `build_seed.py --install`, поэтому соединение не держится открытым.
 */
class SeedLookup(private val file: File) {

    fun find(gtin: String): Product? {
        if (!file.isFile) return null
        val readOnly = SQLiteConfig().apply { setReadOnly(true) }.toProperties()
        return DriverManager.getConnection("jdbc:sqlite:${file.path}", readOnly).use { connection ->
            connection.prepareStatement(
                "SELECT name, brand, kcal100, prot100, fat100, carb100, serving_g FROM product_seed WHERE barcode = ?"
            ).use { stmt ->
                stmt.setString(1, gtin)
                stmt.executeQuery().use { rows ->
                    if (!rows.next()) return null
                    Product(
                        gtin = gtin,
                        name = rows.getString("name"),
                        brand = rows.getString("brand"),
                        nutriments = Nutriments(rows.getInt("kcal100"), rows.getInt("prot100"), rows.getInt("fat100"), rows.getInt("carb100")),
                        servingG = rows.getInt("serving_g").takeUnless { rows.wasNull() },
                    )
                }
            }
        }
    }
}
