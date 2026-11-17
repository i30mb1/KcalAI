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
            // Клетчатка пришла с клиентом позже: у уже созданных таблиц столбца нет, дописываем.
            for (table in listOf("contribution", "product")) {
                if (stmt.executeQuery("SELECT 1 FROM pragma_table_info('$table') WHERE name = 'fiber100'").use { it.next() }.not()) {
                    stmt.executeUpdate("ALTER TABLE $table ADD COLUMN fiber100 INTEGER")
                }
            }
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS contribution_gtin ON contribution(gtin)")
            // Решение по коду: опубликован или отклонён. `upto_id` — последний голос, который
            // видел разработчик; голос новее возвращает код в очередь.
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS decision (
                  gtin TEXT PRIMARY KEY, verdict TEXT NOT NULL,
                  upto_id INTEGER NOT NULL, decided_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    @Synchronized
    fun findProduct(gtin: String): Product? =
        connection.prepareStatement(
            "SELECT gtin, name, brand, kcal100, prot100, fat100, carb100, serving_g, fiber100 FROM product WHERE gtin = ?"
        ).use { stmt ->
            stmt.setString(1, gtin)
            stmt.executeQuery().use { rows -> if (rows.next()) rows.toProduct() else null }
        }

    @Synchronized
    fun upsertProduct(product: Product, now: Long) {
        connection.prepareStatement(
            """
            INSERT INTO product (gtin, name, brand, kcal100, prot100, fat100, carb100, serving_g, updated_at, fiber100)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(gtin) DO UPDATE SET name = excluded.name, brand = excluded.brand,
              kcal100 = excluded.kcal100, prot100 = excluded.prot100, fat100 = excluded.fat100,
              carb100 = excluded.carb100, serving_g = excluded.serving_g, updated_at = excluded.updated_at,
              fiber100 = excluded.fiber100
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
            stmt.setObject(10, product.fiber100)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun insertContribution(c: Contribution, receivedAt: Long) {
        connection.prepareStatement(
            """
            INSERT INTO contribution (gtin, name, kcal100, prot100, fat100, carb100, serving_g, created_at, received_at, fiber100)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            stmt.setObject(10, c.fiber100)
            stmt.executeUpdate()
        }
    }

    fun contributionsFor(gtin: String): List<Contribution> = votesFor(gtin).map { it.contribution }

    /** Все голоса за код, старые первыми; `pending` — пришёл после последнего решения. */
    @Synchronized
    fun votesFor(gtin: String): List<Vote> =
        connection.prepareStatement(
            "SELECT c.id, c.gtin, c.name, c.kcal100, c.prot100, c.fat100, c.carb100, c.serving_g, c.fiber100, c.created_at, c.received_at, " +
                "c.id > COALESCE(d.upto_id, 0) AS pending " +
                "FROM contribution c LEFT JOIN decision d ON d.gtin = c.gtin WHERE c.gtin = ? ORDER BY c.id"
        ).use { stmt ->
            stmt.setString(1, gtin)
            stmt.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(
                        Vote(
                            contribution = Contribution(
                                gtin = rows.getString("gtin"),
                                name = rows.getString("name"),
                                nutriments = rows.toNutriments(),
                                servingG = rows.getInt("serving_g").takeUnless { rows.wasNull() },
                                createdAt = rows.getLong("created_at"),
                                fiber100 = rows.getInt("fiber100").takeUnless { rows.wasNull() },
                            ),
                            receivedAt = rows.getLong("received_at"),
                            pending = rows.getBoolean("pending"),
                        )
                    )
                }
            }
        }

    /** Коды с голосами после последнего решения, свежие первыми. */
    @Synchronized
    fun queue(): List<QueueItem> =
        connection.prepareStatement(
            """
            SELECT q.gtin, q.votes, q.last_at, last.name AS last_name FROM (
              SELECT c.gtin, COUNT(*) AS votes, MAX(c.received_at) AS last_at, MAX(c.id) AS last_id
              FROM contribution c LEFT JOIN decision d ON d.gtin = c.gtin
              WHERE c.id > COALESCE(d.upto_id, 0)
              GROUP BY c.gtin
            ) q JOIN contribution last ON last.id = q.last_id
            ORDER BY q.last_id DESC
            """.trimIndent()
        ).use { stmt ->
            stmt.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(
                        QueueItem(rows.getString("gtin"), rows.getString("last_name"), rows.getInt("votes"), rows.getLong("last_at"))
                    )
                }
            }
        }

    /** Закрывает все голоса за код, пришедшие до этой минуты. */
    @Synchronized
    fun decide(gtin: String, verdict: Verdict, now: Long) {
        connection.prepareStatement(
            """
            INSERT INTO decision (gtin, verdict, upto_id, decided_at)
            VALUES (?, ?, (SELECT COALESCE(MAX(id), 0) FROM contribution WHERE gtin = ?), ?)
            ON CONFLICT(gtin) DO UPDATE SET verdict = excluded.verdict, upto_id = excluded.upto_id,
              decided_at = excluded.decided_at
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, gtin)
            stmt.setString(2, verdict.name.lowercase())
            stmt.setString(3, gtin)
            stmt.setLong(4, now)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun decisionFor(gtin: String): Decision? =
        connection.prepareStatement("SELECT verdict, decided_at FROM decision WHERE gtin = ?").use { stmt ->
            stmt.setString(1, gtin)
            stmt.executeQuery().use { rows ->
                if (rows.next()) Decision(Verdict.valueOf(rows.getString("verdict").uppercase()), rows.getLong("decided_at")) else null
            }
        }

    /** Опубликованное, свежие правки первыми. */
    @Synchronized
    fun products(): List<Product> =
        connection.prepareStatement(
            "SELECT gtin, name, brand, kcal100, prot100, fat100, carb100, serving_g, fiber100 FROM product ORDER BY updated_at DESC"
        ).use { stmt ->
            stmt.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toProduct()) } }
        }

    @Synchronized
    fun deleteProduct(gtin: String) {
        connection.prepareStatement("DELETE FROM product WHERE gtin = ?").use { stmt ->
            stmt.setString(1, gtin)
            stmt.executeUpdate()
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
        fiber100 = getInt("fiber100").takeUnless { wasNull() },
    )

    companion object {
        fun inMemory(): Db = Db(":memory:")
    }
}
