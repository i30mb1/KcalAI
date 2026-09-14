package n7.kcalai.server

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Одно соединение SQLite под замком. Сервер локальный, нагрузка — один телефон;
 * пул соединений тут решал бы несуществующую проблему.
 */
class Db(path: String) : AutoCloseable {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS product (
                  gtin TEXT PRIMARY KEY, name TEXT NOT NULL, brand TEXT,
                  kcal100 INTEGER NOT NULL, prot100 INTEGER NOT NULL,
                  fat100 INTEGER NOT NULL, carb100 INTEGER NOT NULL,
                  serving_g INTEGER, updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS contribution (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  gtin TEXT NOT NULL, name TEXT NOT NULL,
                  kcal100 INTEGER NOT NULL, prot100 INTEGER NOT NULL,
                  fat100 INTEGER NOT NULL, carb100 INTEGER NOT NULL,
                  serving_g INTEGER, created_at INTEGER NOT NULL, received_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS contribution_gtin ON contribution(gtin)")
        }
    }

    @Synchronized
    fun findProduct(gtin: String): Product? =
        connection.prepareStatement(
            "SELECT gtin, name, brand, kcal100, prot100, fat100, carb100, serving_g FROM product WHERE gtin = ?"
        ).use { stmt ->
            stmt.setString(1, gtin)
            stmt.executeQuery().use { rows -> if (rows.next()) rows.toProduct() else null }
        }

    @Synchronized
    fun upsertProduct(product: Product, now: Long) {
        connection.prepareStatement(
            """
            INSERT INTO product (gtin, name, brand, kcal100, prot100, fat100, carb100, serving_g, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(gtin) DO UPDATE SET name = excluded.name, brand = excluded.brand,
              kcal100 = excluded.kcal100, prot100 = excluded.prot100, fat100 = excluded.fat100,
              carb100 = excluded.carb100, serving_g = excluded.serving_g, updated_at = excluded.updated_at
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, product.gtin)
            stmt.setString(2, product.name)
            stmt.setString(3, product.brand)
            stmt.setInt(4, product.nutriments.kcal100)
            stmt.setInt(5, product.nutriments.prot100)
            stmt.setInt(6, product.nutriments.fat100)
            stmt.setInt(7, product.nutriments.carb100)
            stmt.setObject(8, product.servingG)
            stmt.setLong(9, now)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun insertContribution(c: Contribution, receivedAt: Long) {
        connection.prepareStatement(
            """
            INSERT INTO contribution (gtin, name, kcal100, prot100, fat100, carb100, serving_g, created_at, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, c.gtin)
            stmt.setString(2, c.name)
            stmt.setInt(3, c.nutriments.kcal100)
            stmt.setInt(4, c.nutriments.prot100)
            stmt.setInt(5, c.nutriments.fat100)
            stmt.setInt(6, c.nutriments.carb100)
            stmt.setObject(7, c.servingG)
            stmt.setLong(8, c.createdAt)
            stmt.setLong(9, receivedAt)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun contributionsFor(gtin: String): List<Contribution> =
        connection.prepareStatement(
            "SELECT gtin, name, kcal100, prot100, fat100, carb100, serving_g, created_at " +
                "FROM contribution WHERE gtin = ? ORDER BY id"
        ).use { stmt ->
            stmt.setString(1, gtin)
            stmt.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(
                        Contribution(
                            gtin = rows.getString("gtin"),
                            name = rows.getString("name"),
                            nutriments = rows.toNutriments(),
                            servingG = rows.getInt("serving_g").takeUnless { rows.wasNull() },
                            createdAt = rows.getLong("created_at"),
                        )
                    )
                }
            }
        }

    override fun close() = connection.close()

    private fun ResultSet.toNutriments() = Nutriments(
        kcal100 = getInt("kcal100"),
        prot100 = getInt("prot100"),
        fat100 = getInt("fat100"),
        carb100 = getInt("carb100"),
    )

    private fun ResultSet.toProduct() = Product(
        gtin = getString("gtin"),
        name = getString("name"),
        brand = getString("brand"),
        nutriments = toNutriments(),
        servingG = getInt("serving_g").takeUnless { wasNull() },
    )

    companion object {
        fun inMemory(): Db = Db(":memory:")
    }
}
