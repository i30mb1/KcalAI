# Локальный сервер — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ktor-сервер на компьютере разработчика: товары по GTIN, приём вкладов, раздача `seed.db`, приём сессий съёмки; клиент докачивает справочник и отправляет сессии.

**Architecture:** `server/` — самостоятельный Gradle-проект (Ktor + Netty, `sqlite-jdbc`, JSON через `kotlinx.serialization` JsonElement API без компиляторного плагина). Данные в `server/data/`. На клиенте два новых воркера (`SeedUpdateWorker`, `ScanUploadWorker`) рядом с `ContributionWorker`, адрес сервера — из `local.properties` через `BuildConfig`.

**Tech Stack:** Kotlin 2.3.10 (JVM 21), Ktor 3.2.3, `org.xerial:sqlite-jdbc:3.41.2.2`, `kotlinx-serialization-json:1.8.1`, JUnit 4; на клиенте — `HttpURLConnection`, `org.json`, WorkManager.

**Spec:** `specs/2026-09-14-local-server-design.md`

## Global Constraints

- Единицы везде как в `docs/server-contract.md`: `kcal100` — целые ккал; `prot100/fat100/carb100` — сотые грамма; `servingG` — граммы; `createdAt` — мс Unix UTC.
- Инварианты КБЖУ те же, что в `NutrimentValidator`: ккал 0..900, макросы 0..10000 сг каждый, сумма макросов ≤ 10000.
- `server/` не включается в Android-сборку. Ничего из `:core:*` туда не импортируется.
- Комментарии и сообщения — по-русски, в стиле остальных файлов (объяснять *почему*, а не *что*).
- Тесты: JUnit 4, имена в бэктиках по-русски. Без точек в именах тестов (Kotlin запрещает).
- Все изменения коммитятся сразу в `main`. Хвост коммита:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01SB5A6ZFEfFtHDnuxjk21BB
  ```
- Сборка из консоли: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` (PowerShell) перед `gradlew`.

---

## Карта файлов

**Сервер (новое):**
- `server/settings.gradle.kts`, `server/build.gradle.kts`, `server/gradlew`, `server/gradlew.bat`, `server/gradle/wrapper/*` — копия wrapper'а из корня.
- `server/src/main/kotlin/n7/kcalai/server/Main.kt` — `main()`: конфиг из env, `embeddedServer`.
- `server/src/main/kotlin/n7/kcalai/server/App.kt` — `fun Application.kcalServer(db: Db, dataDir: File)`: подключает маршруты.
- `server/src/main/kotlin/n7/kcalai/server/Gtin.kt` — контрольная цифра EAN-8/13.
- `server/src/main/kotlin/n7/kcalai/server/Nutriments.kt` — `Nutriments` + `isPlausible()`.
- `server/src/main/kotlin/n7/kcalai/server/Db.kt` — SQLite: схема, `findProduct`, `insertContribution`.
- `server/src/main/kotlin/n7/kcalai/server/ProductRoutes.kt` — `GET /v1/products/{gtin}`.
- `server/src/main/kotlin/n7/kcalai/server/ContributionRoutes.kt` — `POST /v1/contributions`.
- `server/src/main/kotlin/n7/kcalai/server/SeedRoutes.kt` — манифест и файл.
- `server/src/main/kotlin/n7/kcalai/server/ScanRoutes.kt` — `POST /v1/scans`.
- `server/src/test/kotlin/n7/kcalai/server/*Test.kt` — по тесту на файл маршрутов + `GtinTest`.
- `server/tools/publish.py` — перенос вкладов в `product`.
- `.gitignore` — `/server/data/`, `/server/build`, `/server/.gradle`.

**Клиент (правки):**
- `app/build.gradle.kts` — `BuildConfig.KCAL_SERVER` из `local.properties`.
- `app/src/debug/AndroidManifest.xml`, `app/src/debug/res/xml/network_security_config.xml` — cleartext в debug.
- `app/src/main/java/n7/kcalai/AppContainer.kt` — адрес из `BuildConfig`, `seedSource`, `scanUploader`.
- `core/remote/src/main/java/n7/kcalai/remote/Http.kt` — `download`, `postMultipart`.
- `core/remote/src/main/java/n7/kcalai/remote/KcalServerSource.kt` — `seedManifest()`, `downloadSeed()`, `uploadScan()`; интерфейсы `SeedSource`, `ScanUploader`.
- `core/fooddb/src/main/java/n7/kcalai/fooddb/SeedInstaller.kt` — `.next`, два stamp'а, `sha256(File)`.
- `core/fooddb/src/test/java/n7/kcalai/fooddb/SeedInstallerTest.kt`.
- `app/src/main/java/n7/kcalai/work/SeedUpdateWorker.kt`, `ScanUploadWorker.kt`.
- `app/src/main/java/n7/kcalai/KcalApp.kt` — постановка воркеров.
- `feature/scanner/src/main/java/n7/kcalai/feature/scanner/LabelRecorder.kt` — публичный `labelScansDirectory(context)`.
- `feature/diary/src/main/java/n7/kcalai/feature/diary/DiaryViewModel.kt`, `app/src/main/java/n7/kcalai/MainActivity.kt` — колбэк `onScanFinished`.
- `tools/builddb/build_seed.py` — `--install` пишет и в `server/data/seed/`.
- `README.md`, `docs/server-contract.md` — новые эндпоинты.

---

### Task 1: Каркас сервера и контрольная цифра GTIN

**Files:**
- Create: `server/settings.gradle.kts`, `server/build.gradle.kts`, `server/gradlew`, `server/gradlew.bat`, `server/gradle/wrapper/gradle-wrapper.jar`, `server/gradle/wrapper/gradle-wrapper.properties`
- Create: `server/src/main/kotlin/n7/kcalai/server/Gtin.kt`
- Test: `server/src/test/kotlin/n7/kcalai/server/GtinTest.kt`
- Modify: `.gitignore`

**Interfaces:**
- Produces: `object Gtin { fun isValid(digits: String): Boolean }` — `true` для 8/13 цифр с верной контрольной.

- [ ] **Step 1: Скопировать wrapper и создать сборку**

```powershell
New-Item -ItemType Directory -Force server\gradle\wrapper | Out-Null
Copy-Item gradlew, gradlew.bat server\
Copy-Item gradle\wrapper\gradle-wrapper.jar, gradle\wrapper\gradle-wrapper.properties server\gradle\wrapper\
```

`server/settings.gradle.kts`:
```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "kcal-server"
```

`server/build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm") version "2.3.10"
    application
}

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

val ktor = "3.2.3"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.xerial:sqlite-jdbc:3.41.2.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
}

application { mainClass.set("n7.kcalai.server.MainKt") }
```

Добавить в `.gitignore` (в блок «Android / Gradle»):
```
/server/build
/server/.gradle
/server/data/
```

- [ ] **Step 2: Написать падающий тест**

`server/src/test/kotlin/n7/kcalai/server/GtinTest.kt`:
```kotlin
package n7.kcalai.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Та же проверка, что в клиенте: одна перепутанная цифра не должна попадать в пул. */
class GtinTest {

    @Test
    fun `EAN-13 с верной контрольной цифрой проходит`() {
        assertTrue(Gtin.isValid("4600699500001"))
    }

    @Test
    fun `EAN-8 проходит`() {
        assertTrue(Gtin.isValid("96385074"))
    }

    @Test
    fun `перепутанная цифра, буквы и чужая длина отсекаются`() {
        assertFalse(Gtin.isValid("4600699500002"))
        assertFalse(Gtin.isValid("46006995000"))
        assertFalse(Gtin.isValid("abc"))
        assertFalse(Gtin.isValid(""))
    }
}
```

- [ ] **Step 3: Убедиться, что тест падает**

Run: `cd server; .\gradlew.bat test --tests "n7.kcalai.server.GtinTest"`
Expected: FAIL — `Unresolved reference: Gtin`.

- [ ] **Step 4: Реализовать**

`server/src/main/kotlin/n7/kcalai/server/Gtin.kt`:
```kotlin
package n7.kcalai.server

/**
 * Контрольная цифра GTIN — мод-10 со взвешиванием 3 и 1 от правого края.
 *
 * Клиент уже проверил, но клиент — не то место, на которое сервер вправе
 * полагаться: в пул попадает только то, что сошлось и здесь.
 */
object Gtin {

    fun isValid(digits: String): Boolean {
        if (digits.length != 8 && digits.length != 13) return false
        if (!digits.all(Char::isDigit)) return false

        val body = digits.dropLast(1)
        var sum = 0
        for ((offset, char) in body.reversed().withIndex()) {
            sum += char.digitToInt() * if (offset % 2 == 0) 3 else 1
        }
        return (10 - sum % 10) % 10 == digits.last().digitToInt()
    }
}
```

- [ ] **Step 5: Проверить, что тест проходит**

Run: `cd server; .\gradlew.bat test --tests "n7.kcalai.server.GtinTest"`
Expected: BUILD SUCCESSFUL, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add server .gitignore
git commit -m "feat(server): каркас Ktor-проекта и проверка GTIN"
```

---

### Task 2: База данных и инварианты КБЖУ

**Files:**
- Create: `server/src/main/kotlin/n7/kcalai/server/Nutriments.kt`
- Create: `server/src/main/kotlin/n7/kcalai/server/Db.kt`
- Test: `server/src/test/kotlin/n7/kcalai/server/DbTest.kt`, `server/src/test/kotlin/n7/kcalai/server/NutrimentsTest.kt`

**Interfaces:**
- Produces:
  - `data class Nutriments(val kcal100: Int, val prot100: Int, val fat100: Int, val carb100: Int) { fun isPlausible(): Boolean }`
  - `data class Product(val gtin: String, val name: String, val brand: String?, val nutriments: Nutriments, val servingG: Int?)`
  - `data class Contribution(val gtin: String, val name: String, val nutriments: Nutriments, val servingG: Int?, val createdAt: Long)`
  - `class Db(path: String) : AutoCloseable { fun findProduct(gtin: String): Product?; fun upsertProduct(product: Product, now: Long); fun insertContribution(c: Contribution, receivedAt: Long); fun contributionsFor(gtin: String): List<Contribution> }`
  - `Db.inMemory()` — companion, для тестов (`jdbc:sqlite::memory:`).

- [ ] **Step 1: Тесты**

`NutrimentsTest.kt`:
```kotlin
package n7.kcalai.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutrimentsTest {

    @Test
    fun `обычный продукт правдоподобен`() {
        assertTrue(Nutriments(59, 290, 320, 470).isPlausible())
    }

    @Test
    fun `невозможное отсекается`() {
        assertFalse("отрицательное", Nutriments(59, -1, 0, 0).isPlausible())
        assertFalse("больше чистого жира", Nutriments(901, 0, 10_000, 0).isPlausible())
        assertFalse("макрос больше 100 г", Nutriments(400, 10_001, 0, 0).isPlausible())
        assertFalse("сумма больше 100 г", Nutriments(400, 5_000, 3_000, 3_000).isPlausible())
    }
}
```

`DbTest.kt`:
```kotlin
package n7.kcalai.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DbTest {

    private val db = Db.inMemory()
    private val milk = Product("4600699500001", "Молоко 3,2%", "Домик в деревне", Nutriments(59, 290, 320, 470), 250)

    @Test
    fun `товара нет — null`() {
        assertNull(db.findProduct("4600699500001"))
    }

    @Test
    fun `товар находится после записи, повторная запись обновляет`() {
        db.upsertProduct(milk, now = 1)
        db.upsertProduct(milk.copy(name = "Молоко 3,2% пастеризованное"), now = 2)

        assertEquals("Молоко 3,2% пастеризованное", db.findProduct(milk.gtin)?.name)
    }

    @Test
    fun `вклады копятся все, повтор того же GTIN — ещё один голос`() {
        val vote = Contribution(milk.gtin, milk.name, milk.nutriments, 250, createdAt = 10)
        db.insertContribution(vote, receivedAt = 100)
        db.insertContribution(vote, receivedAt = 101)

        assertEquals(2, db.contributionsFor(milk.gtin).size)
    }
}
```

- [ ] **Step 2: Убедиться, что падают**

Run: `cd server; .\gradlew.bat test`
Expected: FAIL — unresolved `Nutriments`, `Db`.

- [ ] **Step 3: Реализовать**

`Nutriments.kt`:
```kotlin
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
```

`Db.kt`:
```kotlin
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
            "SELECT gtin, name, kcal100, prot100, fat100, carb100, serving_g, created_at FROM contribution WHERE gtin = ? ORDER BY id"
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
```

- [ ] **Step 4: Проверить**

Run: `cd server; .\gradlew.bat test`
Expected: BUILD SUCCESSFUL, тесты `GtinTest`, `NutrimentsTest`, `DbTest` зелёные.

- [ ] **Step 5: Commit**

```bash
git add server
git commit -m "feat(server): SQLite-хранилище товаров и вкладов"
```

---

### Task 3: `GET /v1/products/{gtin}` и точка входа

**Files:**
- Create: `server/src/main/kotlin/n7/kcalai/server/App.kt`, `ProductRoutes.kt`, `Main.kt`
- Test: `server/src/test/kotlin/n7/kcalai/server/ProductRoutesTest.kt`

**Interfaces:**
- Produces: `fun Application.kcalServer(db: Db, dataDir: File)` — единственная точка подключения маршрутов; каждая следующая задача добавляет в неё одну строку `route(...)`. `fun Route.productRoutes(db: Db)`.
- Produces: `fun Product.toJson(): JsonObject` (для ответа).

- [ ] **Step 1: Тест**

```kotlin
package n7.kcalai.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductRoutesTest {

    private val db = Db.inMemory()
    private val dataDir = File(System.getProperty("java.io.tmpdir"), "kcal-test-${System.nanoTime()}")

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application { kcalServer(db, dataDir) }
        block()
    }

    @Test
    fun `товара нет — 404 без тела`() = app {
        val response = client.get("/v1/products/4600699500001")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `битый код — 400`() = app {
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/products/4600699500002").status)
    }

    @Test
    fun `товар отдаётся в единицах контракта`() = app {
        db.upsertProduct(Product("4600699500001", "Молоко 3,2%", "Домик в деревне", Nutriments(59, 290, 320, 470), 250), now = 1)

        val response = client.get("/v1/products/4600699500001")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Молоко 3,2%", json["name"]!!.jsonPrimitive.content)
        assertEquals("320", json["fat100"]!!.jsonPrimitive.content)
        assertEquals("250", json["servingG"]!!.jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: Убедиться, что падает** — `cd server; .\gradlew.bat test --tests "*ProductRoutesTest"` → unresolved `kcalServer`.

- [ ] **Step 3: Реализовать**

`App.kt`:
```kotlin
package n7.kcalai.server

import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File

/** Все маршруты сервера. Тесты поднимают то же самое через `testApplication`. */
fun Application.kcalServer(db: Db, dataDir: File) {
    routing {
        route("/v1") {
            productRoutes(db)
        }
    }
}
```

`ProductRoutes.kt`:
```kotlin
package n7.kcalai.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Только то, что разработчик положил в `product` сам. 404 — штатный ответ, клиент идёт в OFF. */
fun Route.productRoutes(db: Db) {
    get("/products/{gtin}") {
        val gtin = call.parameters["gtin"].orEmpty()
        if (!Gtin.isValid(gtin)) {
            call.respondText("неверная контрольная цифра", status = HttpStatusCode.BadRequest)
            return@get
        }
        val product = db.findProduct(gtin)
        if (product == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respondText(product.toJson().toString(), ContentType.Application.Json)
        }
    }
}

fun Product.toJson(): JsonObject = buildJsonObject {
    put("gtin", gtin)
    put("name", name)
    brand?.let { put("brand", it) }
    put("kcal100", nutriments.kcal100)
    put("prot100", nutriments.prot100)
    put("fat100", nutriments.fat100)
    put("carb100", nutriments.carb100)
    servingG?.let { put("servingG", it) }
}
```

`Main.kt`:
```kotlin
package n7.kcalai.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File

/**
 * Запуск: `./gradlew run`. Слушает все интерфейсы — телефон стучится по IP компьютера.
 * `KCAL_PORT` и `KCAL_DATA` переопределяют порт и каталог данных.
 */
fun main() {
    val port = System.getenv("KCAL_PORT")?.toIntOrNull() ?: 8080
    val dataDir = File(System.getenv("KCAL_DATA") ?: "data").apply { mkdirs() }
    val db = Db(File(dataDir, "kcal-server.db").path)

    println("kcal-server: http://0.0.0.0:$port, данные в ${dataDir.absolutePath}")
    embeddedServer(Netty, port = port, host = "0.0.0.0") { kcalServer(db, dataDir) }
        .start(wait = true)
}
```

- [ ] **Step 4: Проверить** — `cd server; .\gradlew.bat test` → зелёные. Затем `cd server; .\gradlew.bat run` в фоне, `curl http://localhost:8080/v1/products/4600699500001` → `404`. Остановить.

- [ ] **Step 5: Commit**

```bash
git add server
git commit -m "feat(server): GET /v1/products/{gtin} и запуск"
```

---

### Task 4: `POST /v1/contributions`

**Files:**
- Create: `server/src/main/kotlin/n7/kcalai/server/ContributionRoutes.kt`
- Modify: `server/src/main/kotlin/n7/kcalai/server/App.kt` — добавить `contributionRoutes(db)`
- Test: `server/src/test/kotlin/n7/kcalai/server/ContributionRoutesTest.kt`

**Interfaces:**
- Produces: `fun Route.contributionRoutes(db: Db, now: () -> Long = System::currentTimeMillis)`.

- [ ] **Step 1: Тест**

```kotlin
package n7.kcalai.server

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ContributionRoutesTest {

    private val db = Db.inMemory()
    private val dataDir = File(System.getProperty("java.io.tmpdir"), "kcal-test-${System.nanoTime()}")

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application { kcalServer(db, dataDir) }
        block()
    }

    private val milk = """{"gtin":"4600699500001","name":"Молоко 3,2%","kcal100":59,"prot100":290,"fat100":320,"carb100":470,"servingG":250,"createdAt":1757721600000}"""
    private val broken = """{"gtin":"4600699500002","name":"Опечатка","kcal100":59,"prot100":290,"fat100":320,"carb100":470,"createdAt":1}"""
    private val absurd = """{"gtin":"96385074","name":"Жир","kcal100":950,"prot100":0,"fat100":10000,"carb100":0,"createdAt":1}"""

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.send(body: String) =
        client.post("/v1/contributions") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }

    @Test
    fun `принятые попадают в ответ и в базу`() = app {
        val response = send("[$milk]")

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("""{"accepted":["4600699500001"]}""", response.bodyAsText())
        assertEquals(1, db.contributionsFor("4600699500001").size)
    }

    @Test
    fun `битый GTIN и абсурдный КБЖУ не принимаются, остальное — принимается`() = app {
        val response = send("[$milk,$broken,$absurd]")

        assertEquals("""{"accepted":["4600699500001"]}""", response.bodyAsText())
        assertEquals(0, db.contributionsFor("4600699500002").size)
    }

    @Test
    fun `повтор того же GTIN — ещё один голос`() = app {
        send("[$milk]")
        send("[$milk]")

        assertEquals(2, db.contributionsFor("4600699500001").size)
    }

    @Test
    fun `не JSON — 400`() = app {
        assertEquals(HttpStatusCode.BadRequest, send("это не json").status)
        assertEquals(HttpStatusCode.BadRequest, send("""{"gtin":"1"}""").status)
    }
}
```

- [ ] **Step 2: Убедиться, что падает** — `cd server; .\gradlew.bat test --tests "*ContributionRoutesTest"` → 404 вместо 202.

- [ ] **Step 3: Реализовать**

`ContributionRoutes.kt`:
```kotlin
package n7.kcalai.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Вклады пишутся все, без дедупа: повтор того же GTIN — ещё один голос, а не
 * дубликат. Отбраковка — только по инвариантам; порог согласия не здесь,
 * публикация в `product` — ручная.
 */
fun Route.contributionRoutes(db: Db, now: () -> Long = System::currentTimeMillis) {
    post("/contributions") {
        val items = try {
            Json.parseToJsonElement(call.receiveText()) as? JsonArray
        } catch (error: SerializationException) {
            null
        }
        if (items == null) {
            call.respondText("ожидается JSON-массив", status = HttpStatusCode.BadRequest)
            return@post
        }

        val receivedAt = now()
        val accepted = items.mapNotNull { element ->
            val contribution = (element as? JsonObject)?.toContribution() ?: return@mapNotNull null
            if (!Gtin.isValid(contribution.gtin) || !contribution.nutriments.isPlausible()) return@mapNotNull null
            db.insertContribution(contribution, receivedAt)
            contribution.gtin
        }

        val body = buildJsonObject {
            put("accepted", buildJsonArray { accepted.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
        call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.Accepted)
    }
}

/** `null` — не хватает обязательного поля; такой элемент просто не принимается. */
private fun JsonObject.toContribution(): Contribution? {
    fun int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    val name = this["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return Contribution(
        gtin = this["gtin"]?.jsonPrimitive?.contentOrNull ?: return null,
        name = name,
        nutriments = Nutriments(
            kcal100 = int("kcal100") ?: return null,
            prot100 = int("prot100") ?: return null,
            fat100 = int("fat100") ?: return null,
            carb100 = int("carb100") ?: return null,
        ),
        servingG = int("servingG")?.takeIf { it > 0 },
        createdAt = this["createdAt"]?.jsonPrimitive?.longOrNull ?: return null,
    )
}
```

В `App.kt` внутри `route("/v1")` добавить `contributionRoutes(db)`.

- [ ] **Step 4: Проверить** — `cd server; .\gradlew.bat test` → зелёные.

- [ ] **Step 5: Commit**

```bash
git add server
git commit -m "feat(server): POST /v1/contributions — голоса без дедупа"
```

---

### Task 5: Раздача `seed.db` и запись его сборщиком

**Files:**
- Create: `server/src/main/kotlin/n7/kcalai/server/SeedRoutes.kt`
- Modify: `server/src/main/kotlin/n7/kcalai/server/App.kt` — `seedRoutes(File(dataDir, "seed"))`
- Modify: `tools/builddb/build_seed.py:293-302`
- Test: `server/src/test/kotlin/n7/kcalai/server/SeedRoutesTest.kt`

**Interfaces:**
- Produces: `fun Route.seedRoutes(seedDir: File)`. Файлы: `seedDir/seed.db`, `seedDir/seed.db.sha256` (hex), `seedDir/version.txt` (целое).
- Манифест: `{"version": Int, "sha256": String, "size": Long, "url": "/v1/seed/seed.db"}`.

- [ ] **Step 1: Тест**

```kotlin
package n7.kcalai.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SeedRoutesTest {

    private val db = Db.inMemory()
    private val dataDir = File(System.getProperty("java.io.tmpdir"), "kcal-test-${System.nanoTime()}").apply { mkdirs() }

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application { kcalServer(db, dataDir) }
        block()
    }

    @Test
    fun `без файла — 404`() = app {
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/seed/manifest.json").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/seed/seed.db").status)
    }

    @Test
    fun `манифест описывает файл, файл отдаётся целиком`() = app {
        val seed = File(dataDir, "seed").apply { mkdirs() }
        val bytes = byteArrayOf(1, 2, 3, 4)
        File(seed, "seed.db").writeBytes(bytes)
        File(seed, "seed.db.sha256").writeText("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a")
        File(seed, "version.txt").writeText("7")

        val manifest = Json.parseToJsonElement(client.get("/v1/seed/manifest.json").bodyAsText()).jsonObject
        assertEquals("7", manifest["version"]!!.jsonPrimitive.content)
        assertEquals("4", manifest["size"]!!.jsonPrimitive.content)
        assertEquals("/v1/seed/seed.db", manifest["url"]!!.jsonPrimitive.content)
        assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", manifest["sha256"]!!.jsonPrimitive.content)

        assertArrayEquals(bytes, client.get("/v1/seed/seed.db").bodyAsBytes())
    }
}
```

- [ ] **Step 2: Убедиться, что падает** — второй тест: 404 вместо манифеста.

- [ ] **Step 3: Реализовать**

`SeedRoutes.kt`:
```kotlin
package n7.kcalai.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.LocalFileContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Справочник, собранный `build_seed.py --install`. Хеш и версия лежат рядом
 * с файлом: считать хеш на каждый запрос незачем, файл меняется раз в день.
 */
fun Route.seedRoutes(seedDir: File) {
    val file = File(seedDir, "seed.db")
    val digest = File(seedDir, "seed.db.sha256")
    val version = File(seedDir, "version.txt")

    get("/seed/manifest.json") {
        if (!file.isFile || !digest.isFile) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val body = buildJsonObject {
            put("version", version.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0)
            put("sha256", digest.readText().trim())
            put("size", file.length())
            put("url", "/v1/seed/seed.db")
        }
        call.respondText(body.toString(), ContentType.Application.Json)
    }

    get("/seed/seed.db") {
        if (!file.isFile) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respond(LocalFileContent(file, ContentType.Application.OctetStream))
    }
}
```

В `App.kt`: `seedRoutes(File(dataDir, "seed"))`.

`tools/builddb/build_seed.py` — после записи в assets (внутри `if args.install:`):
```python
        # Та же сборка уезжает и на локальный сервер: телефон докачает её без релиза APK.
        # Версия — просто счётчик установок; клиент сравнивает хеш, а версия для глаз.
        server_seed = HERE.parent.parent / "server" / "data" / "seed"
        server_seed.mkdir(parents=True, exist_ok=True)
        shutil.copy2(out, server_seed / "seed.db")
        (server_seed / "seed.db.sha256").write_text(digest, encoding="utf-8")
        version_file = server_seed / "version.txt"
        version = int(version_file.read_text().strip() or 0) + 1 if version_file.exists() else 1
        version_file.write_text(str(version), encoding="utf-8")
        print(f"ОК: сервер получит версию {version} в {server_seed}")
```

- [ ] **Step 4: Проверить** — `cd server; .\gradlew.bat test` → зелёные. `python tools/builddb/build_seed.py --install` → появляется `server/data/seed/version.txt` = `1`.

- [ ] **Step 5: Commit**

```bash
git add server tools/builddb/build_seed.py
git commit -m "feat(server): раздача seed.db; сборщик кладёт его на сервер"
```

---

### Task 6: `POST /v1/scans` и `publish.py`

**Files:**
- Create: `server/src/main/kotlin/n7/kcalai/server/ScanRoutes.kt`, `server/tools/publish.py`
- Modify: `server/src/main/kotlin/n7/kcalai/server/App.kt` — `scanRoutes(File(dataDir, "scans"))`
- Test: `server/src/test/kotlin/n7/kcalai/server/ScanRoutesTest.kt`

**Interfaces:**
- Produces: `fun Route.scanRoutes(scansDir: File)`. Multipart-поля: `readings` (текст), `frame` (файлы, `filename` сохраняется). Заголовок `X-Scan-Id` — `[a-z0-9-]{1,64}`.

- [ ] **Step 1: Тест**

```kotlin
package n7.kcalai.server

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScanRoutesTest {

    private val db = Db.inMemory()
    private val dataDir = File(System.getProperty("java.io.tmpdir"), "kcal-test-${System.nanoTime()}").apply { mkdirs() }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { kcalServer(db, dataDir) }
        block()
    }

    private suspend fun ApplicationTestBuilder.upload(id: String, readings: String, frame: ByteArray) =
        client.post("/v1/scans") {
            header("X-Scan-Id", id)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("readings", readings)
                        append(
                            "frame", frame,
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"frame-00.jpg\"")
                            }
                        )
                    }
                )
            )
        }

    @Test
    fun `сессия ложится в свой каталог`() = app {
        val response = upload("scan-1757721600000", "frame-00.jpg  +0 мс  итог: ок\n", byteArrayOf(7, 7, 7))

        assertEquals(HttpStatusCode.Created, response.status)
        val dir = File(dataDir, "scans/scan-1757721600000")
        assertEquals("frame-00.jpg  +0 мс  итог: ок\n", File(dir, "readings.txt").readText())
        assertArrayEquals(byteArrayOf(7, 7, 7), File(dir, "frame-00.jpg").readBytes())
    }

    @Test
    fun `повторная загрузка не перезаписывает`() = app {
        upload("scan-1", "первая", byteArrayOf(1))
        val response = upload("scan-1", "вторая", byteArrayOf(2))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("первая", File(dataDir, "scans/scan-1/readings.txt").readText())
    }

    @Test
    fun `имя сессии с чужими символами — 400`() = app {
        assertEquals(HttpStatusCode.BadRequest, upload("../etc", "x", byteArrayOf(1)).status)
        assertFalse(File(dataDir, "etc").exists())
    }

    @Test
    fun `без заголовка — 400`() = app {
        val response = client.post("/v1/scans") {
            setBody(MultiPartFormDataContent(formData { append("readings", "x") }))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
```

- [ ] **Step 2: Убедиться, что падает** — 404 вместо 201.

- [ ] **Step 3: Реализовать**

`ScanRoutes.kt`:
```kotlin
package n7.kcalai.server

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray
import java.io.File

/**
 * Сессии съёмки этикеток — кадры и расшифровка из `LabelRecorder` на телефоне.
 *
 * Ложатся как есть, по каталогу на сессию: анализировать их будут глазами
 * и скриптами, и любая база тут только мешала бы. Загрузка идемпотентна —
 * воркер на телефоне может прислать одно и то же дважды.
 */
fun Route.scanRoutes(scansDir: File) {
    post("/scans") {
        val id = call.request.headers["X-Scan-Id"]
        if (id == null || !SCAN_ID.matches(id)) {
            call.respondText("X-Scan-Id: ожидается [a-z0-9-]{1,64}", status = HttpStatusCode.BadRequest)
            return@post
        }

        val target = File(scansDir, id)
        if (target.exists()) {
            call.respond(HttpStatusCode.OK)
            return@post
        }

        // Сначала во временный каталог: оборванная загрузка не должна выглядеть
        // как готовая сессия, которую повторная попытка уже не перезапишет.
        val staging = File(scansDir, "$id.part").apply { deleteRecursively(); mkdirs() }
        var readings: String? = null
        var frames = 0
        call.receiveMultipart().forEachPart { part ->
            when (part) {
                is PartData.FormItem -> if (part.name == "readings") readings = part.value
                is PartData.FileItem -> {
                    val name = part.originalFileName?.let(::File)?.name
                    if (part.name == "frame" && name != null && FRAME_NAME.matches(name)) {
                        File(staging, name).writeBytes(part.provider().toByteArray())
                        frames++
                    }
                }
                else -> Unit
            }
            part.dispose()
        }

        if (readings == null || frames == 0) {
            staging.deleteRecursively()
            call.respondText("нужны readings и хотя бы один frame", status = HttpStatusCode.BadRequest)
            return@post
        }

        File(staging, "readings.txt").writeText(readings!!)
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            call.respondText("не удалось сохранить сессию", status = HttpStatusCode.InternalServerError)
            return@post
        }
        call.respond(HttpStatusCode.Created)
    }
}

private val SCAN_ID = Regex("[a-z0-9-]{1,64}")
private val FRAME_NAME = Regex("frame-\\d{2,3}\\.jpg")
```

В `App.kt`: `scanRoutes(File(dataDir, "scans"))`.

`server/tools/publish.py`:
```python
"""Перенос вкладов в таблицу товаров.

    python publish.py 4600699500001            # медиана по всем вкладам этого GTIN
    python publish.py 4600699500001 --show     # только показать вклады
    python publish.py 4600699500001 --name "Молоко 3,2%" --kcal 59 --prot 290 --fat 320 --carb 470 --serving 250

Порога согласия у сервера нет намеренно: публикует человек, посмотрев на вклады.
"""
import argparse
import sqlite3
import statistics
import time
from pathlib import Path

DB = Path(__file__).parent.parent / "data" / "kcal-server.db"


def main() -> int:
    parser = argparse.ArgumentParser(description="Публикация вкладов в product")
    parser.add_argument("gtin")
    parser.add_argument("--show", action="store_true")
    parser.add_argument("--name")
    parser.add_argument("--brand")
    parser.add_argument("--kcal", type=int)
    parser.add_argument("--prot", type=int)
    parser.add_argument("--fat", type=int)
    parser.add_argument("--carb", type=int)
    parser.add_argument("--serving", type=int)
    args = parser.parse_args()

    conn = sqlite3.connect(DB)
    rows = conn.execute(
        "SELECT name, kcal100, prot100, fat100, carb100, serving_g, received_at "
        "FROM contribution WHERE gtin = ? ORDER BY id",
        (args.gtin,),
    ).fetchall()

    for name, kcal, prot, fat, carb, serving, received in rows:
        print(f"{name!r:40} {kcal:4} ккал  Б {prot/100:5.2f}  Ж {fat/100:5.2f}  У {carb/100:5.2f}  порция {serving}")
    if args.show:
        return 0
    if not rows and args.kcal is None:
        print("вкладов нет, а значения не заданы")
        return 1

    def pick(index, override):
        if override is not None:
            return override
        return int(statistics.median(r[index] for r in rows))

    name = args.name or rows[-1][0]
    serving = args.serving if args.serving is not None else next((r[5] for r in reversed(rows) if r[5]), None)
    values = (args.gtin, name, args.brand, pick(1, args.kcal), pick(2, args.prot), pick(3, args.fat), pick(4, args.carb), serving, int(time.time() * 1000))
    conn.execute(
        "INSERT INTO product (gtin, name, brand, kcal100, prot100, fat100, carb100, serving_g, updated_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(gtin) DO UPDATE SET name = excluded.name, "
        "brand = excluded.brand, kcal100 = excluded.kcal100, prot100 = excluded.prot100, "
        "fat100 = excluded.fat100, carb100 = excluded.carb100, serving_g = excluded.serving_g, "
        "updated_at = excluded.updated_at",
        values,
    )
    conn.commit()
    print(f"ОК: {args.gtin} -> {name!r} {values[3]} ккал")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: Проверить** — `cd server; .\gradlew.bat test` → зелёные (все пять тестовых классов).

- [ ] **Step 5: Commit**

```bash
git add server
git commit -m "feat(server): POST /v1/scans и publish.py"
```

---

### Task 7: Адрес сервера из `local.properties`, cleartext в debug

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/debug/AndroidManifest.xml`, `app/src/debug/res/xml/network_security_config.xml`
- Modify: `app/src/main/java/n7/kcalai/AppContainer.kt:118-121`

**Interfaces:**
- Produces: `BuildConfig.KCAL_SERVER: String` (пусто — сервера нет).

- [ ] **Step 1: `app/build.gradle.kts`**

В начало файла (после `plugins`):
```kotlin
import java.util.Properties

// Адрес локального сервера — в local.properties (не в git): kcal.server=http://192.168.1.10:8080
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
```

В `defaultConfig`:
```kotlin
        buildConfigField("String", "KCAL_SERVER", "\"${localProperties.getProperty("kcal.server", "")}\"")
```

В `buildFeatures`: `buildConfig = true`.

- [ ] **Step 2: Debug-манифест**

`app/src/debug/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Локальный сервер отвечает по http без TLS. Только в debug: release этот оверлей не видит. -->
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
```

`app/src/debug/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

- [ ] **Step 3: `AppContainer`**

Заменить `const val SERVER_BASE_URL = ""` и его комментарий на:
```kotlin
        /** Из `local.properties` (`kcal.server=…`). Пустая строка означает «источника нет». */
        val SERVER_BASE_URL: String = BuildConfig.KCAL_SERVER
```
(`private companion object` остаётся; `val` вместо `const val`.)

- [ ] **Step 4: Проверить** — `.\gradlew.bat :app:assembleDebug` → успех. В `local.properties` добавить `kcal.server=http://<IP компьютера>:8080` (узнать: `ipconfig` → IPv4 Wi-Fi), пересобрать.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/debug app/src/main/java/n7/kcalai/AppContainer.kt
git commit -m "feat(app): адрес сервера из local.properties, cleartext в debug"
```

---

### Task 8: `SeedInstaller` — `.next` и два stamp'а

**Files:**
- Modify: `core/fooddb/src/main/java/n7/kcalai/fooddb/SeedInstaller.kt`
- Test: `core/fooddb/src/test/java/n7/kcalai/fooddb/SeedInstallerTest.kt`

**Interfaces:**
- Produces (все в `object SeedInstaller`):
  - `fun sha256(file: File): String` — hex.
  - `fun installedDigest(dir: File): String?` — содержимое `seed.db.stamp`, если есть.
  - `fun stagedDigest(dir: File): String?` — содержимое `seed.db.next.sha256`, если `.next` есть.
  - `fun stage(dir: File, downloaded: File, expectedSha256: String): Boolean` — проверяет хеш, переносит в `seed.db.next` + пишет `.next.sha256`; `false` и удаляет, если хеш не сошёлся.
  - `fun promoteNext(dir: File): Boolean` — если `.next` есть и его хеш равен `.next.sha256`, переименовывает в `seed.db`, пишет `seed.db.stamp`; иначе удаляет `.next`. Возвращает, была ли подмена.
- Файлы в `dir`: `seed.db`, `seed.db.stamp` (хеш установленного), `seed.db.asset` (хеш ассета, из которого ставили), `seed.db.next`, `seed.db.next.sha256`.

- [ ] **Step 1: Тест**

```kotlin
package n7.kcalai.fooddb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Докачанный справочник встаёт на место только при следующем запуске — под
 * открытым соединением базу не подменяют. Здесь — та часть, что живёт над File
 * и не требует Context.
 */
class SeedInstallerTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "seed-test-${System.nanoTime()}").apply { mkdirs() }

    private fun file(name: String, content: String) = File(dir, name).apply { writeText(content) }

    @Test
    fun `sha256 файла — hex`() {
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            SeedInstaller.sha256(file("x", "test")),
        )
    }

    @Test
    fun `подготовленный файл с верным хешем становится next`() {
        val downloaded = file("dl.tmp", "test")

        assertTrue(SeedInstaller.stage(dir, downloaded, "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"))
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", SeedInstaller.stagedDigest(dir))
        assertFalse(downloaded.exists())
    }

    @Test
    fun `битая закачка не становится next`() {
        val downloaded = file("dl.tmp", "обрезано")

        assertFalse(SeedInstaller.stage(dir, downloaded, "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"))
        assertNull(SeedInstaller.stagedDigest(dir))
        assertFalse(downloaded.exists())
    }

    @Test
    fun `next продвигается в seed и пишет stamp`() {
        file("seed.db", "старая")
        file("seed.db.stamp", "old")
        SeedInstaller.stage(dir, file("dl.tmp", "test"), "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")

        assertTrue(SeedInstaller.promoteNext(dir))
        assertEquals("test", File(dir, "seed.db").readText())
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", SeedInstaller.installedDigest(dir))
        assertNull(SeedInstaller.stagedDigest(dir))
    }

    @Test
    fun `next с испорченным содержимым выбрасывается, seed остаётся`() {
        file("seed.db", "старая")
        file("seed.db.next", "испорчено")
        file("seed.db.next.sha256", "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")

        assertFalse(SeedInstaller.promoteNext(dir))
        assertEquals("старая", File(dir, "seed.db").readText())
        assertFalse(File(dir, "seed.db.next").exists())
    }

    @Test
    fun `без next продвигать нечего`() {
        assertFalse(SeedInstaller.promoteNext(dir))
    }
}
```

- [ ] **Step 2: Убедиться, что падает** — `.\gradlew.bat :core:fooddb:testDebugUnitTest` → unresolved `sha256`/`stage`.

- [ ] **Step 3: Реализовать** — заменить `SeedInstaller` целиком:

```kotlin
package n7.kcalai.fooddb

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Раскладывает `seed.db` из assets в `filesDir` и подхватывает докачанный.
 *
 * SQLite не умеет открывать файл внутри APK, поэтому справочник обязан оказаться
 * на файловой системе.
 *
 * Два источника, и у каждого своя отметка. `seed.db.asset` — хеш ассета, из которого
 * ставили в прошлый раз: изменился ассет (обновилось приложение) — ставим из него,
 * а всё докачанное выбрасываем: релиз новее. `seed.db.stamp` — хеш того, что стоит
 * сейчас; по нему воркер обновления решает, есть ли на сервере что-то новое.
 *
 * Докачанный файл живёт в `seed.db.next` до следующего запуска: под открытым
 * соединением базу не подменяют.
 */
object SeedInstaller {

    private const val ASSET_NAME = "seed.db"
    private const val ASSET_DIGEST = "seed.db.sha256"
    private const val DB_NAME = "seed.db"
    private const val STAMP_NAME = "seed.db.stamp"
    private const val ASSET_STAMP_NAME = "seed.db.asset"
    private const val NEXT_NAME = "seed.db.next"
    private const val NEXT_DIGEST_NAME = "seed.db.next.sha256"

    /** @return абсолютный путь к готовому к открытию seed.db */
    fun install(context: Context): String {
        val dir = context.filesDir
        val target = File(dir, DB_NAME)
        val assetStamp = File(dir, ASSET_STAMP_NAME)

        val expected = context.assets.open(ASSET_DIGEST).use { it.readBytes().decodeToString().trim() }

        if (target.exists() && assetStamp.exists() && assetStamp.readText() == expected) {
            promoteNext(dir)
            return target.absolutePath
        }

        // Пишем во временный файл и переименовываем: прерванное копирование не должно
        // оставить обрезанную базу, которую следующий запуск примет за целую.
        val tmp = File(dir, "$DB_NAME.tmp")
        context.assets.open(ASSET_NAME).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "не удалось установить seed.db в ${target.absolutePath}" }
        File(dir, NEXT_NAME).delete()
        File(dir, NEXT_DIGEST_NAME).delete()

        // Отметки пишутся последними: упади копирование — на следующем запуске
        // несовпадение отметки заставит повторить установку.
        File(dir, STAMP_NAME).writeText(expected)
        assetStamp.writeText(expected)

        return target.absolutePath
    }

    fun installedDigest(dir: File): String? = File(dir, STAMP_NAME).takeIf { it.isFile }?.readText()?.trim()

    fun stagedDigest(dir: File): String? =
        if (File(dir, NEXT_NAME).isFile) File(dir, NEXT_DIGEST_NAME).takeIf { it.isFile }?.readText()?.trim() else null

    /**
     * Скачанный файл -> `seed.db.next`, если хеш сошёлся. Не сошёлся — файл удаляется:
     * обрезанная закачка не должна дожидаться следующего запуска.
     */
    fun stage(dir: File, downloaded: File, expectedSha256: String): Boolean {
        val actual = sha256(downloaded)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            downloaded.delete()
            return false
        }
        val next = File(dir, NEXT_NAME)
        next.delete()
        if (!downloaded.renameTo(next)) {
            downloaded.delete()
            return false
        }
        File(dir, NEXT_DIGEST_NAME).writeText(actual)
        return true
    }

    /** `seed.db.next` -> `seed.db`, если содержимое цело. Иначе `.next` выбрасывается. */
    fun promoteNext(dir: File): Boolean {
        val next = File(dir, NEXT_NAME)
        val digestFile = File(dir, NEXT_DIGEST_NAME)
        if (!next.isFile) {
            digestFile.delete()
            return false
        }
        val expected = digestFile.takeIf { it.isFile }?.readText()?.trim()
        if (expected == null || !sha256(next).equals(expected, ignoreCase = true)) {
            next.delete()
            digestFile.delete()
            return false
        }
        val target = File(dir, DB_NAME)
        if (target.exists()) target.delete()
        if (!next.renameTo(target)) {
            next.delete()
            digestFile.delete()
            return false
        }
        digestFile.delete()
        File(dir, STAMP_NAME).writeText(expected)
        return true
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Проверить** — `.\gradlew.bat :core:fooddb:testDebugUnitTest` → 6 тестов зелёные (плюс `MatchesQueryTest`).

- [ ] **Step 5: Commit**

```bash
git add core/fooddb
git commit -m "feat(fooddb): SeedInstaller подхватывает докачанный seed.db.next"
```

---

### Task 9: Сеть: манифест, закачка, multipart

**Files:**
- Modify: `core/remote/src/main/java/n7/kcalai/remote/Http.kt`
- Modify: `core/remote/src/main/java/n7/kcalai/remote/KcalServerSource.kt`
- Modify: `app/src/main/java/n7/kcalai/AppContainer.kt`

**Interfaces:**
- Produces в `:core:remote`:
  - `data class SeedManifest(val version: Int, val sha256: String, val size: Long, val url: String)` — `url` уже абсолютный.
  - `interface SeedSource { suspend fun seedManifest(): SeedManifest?; suspend fun downloadSeed(manifest: SeedManifest, to: File): Boolean }`
  - `data class ScanFrame(val name: String, val bytes: ByteArray)`
  - `fun interface ScanUploader { suspend fun uploadScan(id: String, readings: String, frames: List<ScanFrame>): Boolean }`
  - `KcalServerSource : RemoteProductSource, ContributionUploader, SeedSource, ScanUploader`.
  - `Http.download(url, userAgent, to: File): Boolean`, `Http.postMultipart(url, userAgent, headers: Map<String,String>, fields: Map<String,String>, files: List<Triple<String,String,ByteArray>>): HttpResponse?`.
- В `AppContainer`: `val seedSource: SeedSource get() = kcalServer`, `val scanUploader: ScanUploader get() = kcalServer`.

- [ ] **Step 1: `Http.kt`** — добавить в `object Http`:

```kotlin
    /** Файл целиком на диск, потоком: справочник — мегабайты, в память его не читаем. */
    suspend fun download(url: String, userAgent: String, to: File): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                setRequestProperty("User-Agent", userAgent)
            }
            if (connection.responseCode !in 200..299) return@withContext false
            connection.inputStream.use { input -> to.outputStream().use { output -> input.copyTo(output) } }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            Log.i(TAG, "GET $url (файл) не удался: ${error.message}")
            to.delete()
            false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * multipart/form-data руками: OkHttp ради одного запроса в проекте не заводят.
     *
     * @param files тройки (имя поля, имя файла, байты)
     */
    suspend fun postMultipart(
        url: String,
        userAgent: String,
        headers: Map<String, String>,
        fields: Map<String, String>,
        files: List<Triple<String, String, ByteArray>>,
    ): HttpResponse? = withContext(Dispatchers.IO) {
        val boundary = "kcal-" + System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            connection.outputStream.buffered().use { out ->
                fun line(text: String) = out.write("$text\r\n".toByteArray(Charsets.UTF_8))
                fields.forEach { (name, value) ->
                    line("--$boundary")
                    line("Content-Disposition: form-data; name=\"$name\"")
                    line("Content-Type: text/plain; charset=utf-8")
                    line("")
                    line(value)
                }
                files.forEach { (field, fileName, bytes) ->
                    line("--$boundary")
                    line("Content-Disposition: form-data; name=\"$field\"; filename=\"$fileName\"")
                    line("Content-Type: application/octet-stream")
                    line("")
                    out.write(bytes)
                    line("")
                }
                line("--$boundary--")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            Log.i(TAG, "POST $url (multipart) не удался: ${error.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
```
Добавить `import java.io.File`.

- [ ] **Step 2: `KcalServerSource.kt`** — добавить типы и реализации:

```kotlin
data class SeedManifest(val version: Int, val sha256: String, val size: Long, val url: String)

/** Свежий справочник с сервера. `null`/`false` — сервера нет или он молчит; это штатно. */
interface SeedSource {
    suspend fun seedManifest(): SeedManifest?
    suspend fun downloadSeed(manifest: SeedManifest, to: File): Boolean
}

class ScanFrame(val name: String, val bytes: ByteArray)

/** Сессия съёмки этикетки уезжает на сервер целиком. `true` — принято (в том числе повторно). */
fun interface ScanUploader {
    suspend fun uploadScan(id: String, readings: String, frames: List<ScanFrame>): Boolean
}
```

В классе: `) : RemoteProductSource, ContributionUploader, SeedSource, ScanUploader {` и методы:

```kotlin
    override suspend fun seedManifest(): SeedManifest? {
        val base = root ?: return null
        val response = Http.get("$base/v1/seed/manifest.json", userAgent) ?: return null
        if (!response.isSuccess) return null
        return try {
            val json = JSONObject(response.body)
            val url = json.getString("url").let { if (it.startsWith("http")) it else base + it }
            SeedManifest(
                version = json.optInt("version", 0),
                sha256 = json.getString("sha256"),
                size = json.optLong("size", 0),
                url = url,
            )
        } catch (error: org.json.JSONException) {
            Log.w(TAG, "манифест справочника не разобрался", error)
            null
        }
    }

    override suspend fun downloadSeed(manifest: SeedManifest, to: File): Boolean =
        Http.download(manifest.url, userAgent, to)

    override suspend fun uploadScan(id: String, readings: String, frames: List<ScanFrame>): Boolean {
        val base = root ?: return false
        val response = Http.postMultipart(
            url = "$base/v1/scans",
            userAgent = userAgent,
            headers = mapOf("X-Scan-Id" to id),
            fields = mapOf("readings" to readings),
            files = frames.map { Triple("frame", it.name, it.bytes) },
        ) ?: return false
        if (!response.isSuccess) Log.i(TAG, "сессия $id не принята: HTTP ${response.code}")
        return response.isSuccess
    }
```
Добавить `import java.io.File`.

- [ ] **Step 3: `AppContainer`** — после `contributionUploader`:
```kotlin
    val seedSource: SeedSource get() = kcalServer
    val scanUploader: ScanUploader get() = kcalServer
```
и импорты `n7.kcalai.remote.SeedSource`, `n7.kcalai.remote.ScanUploader`.

- [ ] **Step 4: Проверить** — `.\gradlew.bat compileDebugKotlin` → успех.

- [ ] **Step 5: Commit**

```bash
git add core/remote app/src/main/java/n7/kcalai/AppContainer.kt
git commit -m "feat(remote): манифест и закачка seed.db, отправка сессий съёмки"
```

---

### Task 10: `SeedUpdateWorker`

**Files:**
- Create: `app/src/main/java/n7/kcalai/work/SeedUpdateWorker.kt`
- Modify: `app/src/main/java/n7/kcalai/KcalApp.kt`

**Interfaces:**
- Consumes: `AppContainer.seedSource`, `SeedInstaller.installedDigest/stagedDigest/stage`.
- Produces: `SeedUpdateWorker.enqueue(context)`.

- [ ] **Step 1: Воркер**

```kotlin
package n7.kcalai.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import n7.kcalai.KcalApp
import n7.kcalai.fooddb.SeedInstaller

/**
 * Докачка справочника с локального сервера.
 *
 * Скачанное не встаёт на место сразу: `FoodDb` держит соединение с текущим файлом,
 * и подменять его под ним нельзя. Файл ложится в `seed.db.next`, а при следующем
 * запуске `SeedInstaller.install` его продвигает. Никаких повторов: нет сети или
 * сервера — попробуем при следующем старте.
 */
class SeedUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as KcalApp).container
        val dir = applicationContext.filesDir

        val manifest = container.seedSource.seedManifest() ?: return Result.success()
        val installed = SeedInstaller.installedDigest(dir)
        val staged = SeedInstaller.stagedDigest(dir)
        if (manifest.sha256.equals(installed, ignoreCase = true) || manifest.sha256.equals(staged, ignoreCase = true)) {
            return Result.success()
        }

        val tmp = File(dir, "seed.db.download")
        if (!container.seedSource.downloadSeed(manifest, tmp)) {
            tmp.delete()
            return Result.success()
        }
        val staged2 = SeedInstaller.stage(dir, tmp, manifest.sha256)
        Log.i(TAG, if (staged2) "справочник v${manifest.version} докачан, встанет при следующем запуске" else "закачка справочника битая, выброшена")
        return Result.success()
    }

    companion object {
        private const val TAG = "SeedUpdateWorker"
        private const val WORK_NAME = "seed-update"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SeedUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
```

- [ ] **Step 2: `KcalApp.onCreate`** — после `ContributionWorker.enqueue(this)`:
```kotlin
        // Справочник: спросить сервер, нет ли свежее. Ставится при каждом старте,
        // работает только при сети и только если есть адрес сервера.
        if (container.contributionsEnabled) SeedUpdateWorker.enqueue(this)
```
Импорт `n7.kcalai.work.SeedUpdateWorker`. Переименовать `contributionsEnabled` в `serverConfigured` в `AppContainer` и `ContributionWorker` (это одно и то же условие — есть адрес).

- [ ] **Step 3: Проверить** — `.\gradlew.bat :app:assembleDebug`. Ручная проверка: сервер запущен, `build_seed.py --install` выполнен, `kcal.server` в `local.properties`; установить APK, запустить, в logcat `SeedUpdateWorker: справочник v1 докачан`; перезапустить приложение — в logcat `SeedInstaller` не ругается, поиск работает.

- [ ] **Step 4: Commit**

```bash
git add app
git commit -m "feat(app): SeedUpdateWorker докачивает справочник"
```

---

### Task 11: `ScanUploadWorker`

**Files:**
- Modify: `feature/scanner/src/main/java/n7/kcalai/feature/scanner/LabelRecorder.kt` — публичная функция каталога
- Create: `app/src/main/java/n7/kcalai/work/ScanUploadWorker.kt`
- Modify: `app/src/main/java/n7/kcalai/KcalApp.kt`, `app/src/main/java/n7/kcalai/MainActivity.kt:32`
- Modify: `feature/diary/src/main/java/n7/kcalai/feature/diary/DiaryViewModel.kt` — колбэк `onScanFinished`

**Interfaces:**
- Produces: `fun labelScansDirectory(context: Context): File` (top-level, public, в `LabelRecorder.kt`); `ScanUploadWorker.enqueue(context)`; `DiaryViewModel(..., onScanFinished: () -> Unit = {})` и то же в `Factory`.

- [ ] **Step 1: `LabelRecorder.kt`** — вынести каталог наружу. Заменить `fun directory(context: Context): File = File(context.filesDir, "label-scans")` в companion на `fun directory(context: Context): File = labelScansDirectory(context)` и добавить top-level после класса:

```kotlin
/**
 * Каталог сессий съёмки. Публичный, потому что читает его не только записывающий:
 * воркер отправки в `:app` обходит его и ставит маркеры `.uploaded`.
 */
fun labelScansDirectory(context: Context): File = File(context.filesDir, "label-scans")
```

Комментарий про внутреннее хранилище (`filesDir`) перенести к этой функции.

- [ ] **Step 2: Воркер**

```kotlin
package n7.kcalai.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit
import n7.kcalai.KcalApp
import n7.kcalai.feature.scanner.labelScansDirectory
import n7.kcalai.remote.ScanFrame

/**
 * Отправка сессий съёмки этикеток на локальный сервер — материал для правки разбора.
 *
 * Сессии, которые ушли, помечаются файлом `.uploaded` и остаются на месте:
 * прополку «последние пять» делает `LabelRecorder`, а отправка не вправе
 * удалять то, что человек, может быть, ещё захочет посмотреть на телефоне.
 */
class ScanUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as KcalApp).container
        if (!container.serverConfigured) return Result.success()

        val sessions = labelScansDirectory(applicationContext)
            .listFiles { file -> file.isDirectory && file.name.startsWith("scan-") }
            .orEmpty()
            .filter { !File(it, MARKER).exists() && File(it, "readings.txt").isFile }
            .sortedBy { it.name }
        if (sessions.isEmpty()) return Result.success()

        var failed = false
        for (session in sessions) {
            val frames = session.listFiles { file -> file.name.endsWith(".jpg") }
                .orEmpty()
                .sortedBy { it.name }
                .map { ScanFrame(it.name, it.readBytes()) }
            if (frames.isEmpty()) continue

            val sent = container.scanUploader.uploadScan(
                id = session.name,
                readings = File(session, "readings.txt").readText(),
                frames = frames,
            )
            if (sent) {
                File(session, MARKER).writeText("")
                Log.i(TAG, "сессия ${session.name} отправлена (${frames.size} кадров)")
            } else {
                failed = true
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "ScanUploadWorker"
        private const val WORK_NAME = "scan-upload"
        const val MARKER = ".uploaded"

        /**
         * Задержка — потому что `LabelRecorder.save` пишет кадры в отдельном потоке
         * уже после закрытия экрана, и воркер без паузы застал бы каталог пустым.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScanUploadWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
```

Убедиться, что `:app` видит `:feature:scanner`: в `app/build.gradle.kts` он приходит транзитивно через `api(project(":feature:scanner"))` в `:feature:diary` — импорт `n7.kcalai.feature.scanner.labelScansDirectory` компилируется. Если нет — добавить `implementation(project(":feature:scanner"))`.

- [ ] **Step 3: `DiaryViewModel`** — новый параметр конструктора и `Factory` после `onContributionQueued`:
```kotlin
    /** Экран съёмки этикетки закрылся — сессия записана, её можно отправлять. */
    private val onScanFinished: () -> Unit = {},
```
Вызывать `onScanFinished()` в `onSaveNewProduct` (в начале), в `onLabelDebugRead` (в начале) и в `onDismissOverlay`:
```kotlin
    fun onDismissOverlay() {
        val closing = overlay.value
        overlay.value = Overlay.None
        if (closing is Overlay.LabelScan || closing == Overlay.LabelDebug) onScanFinished()
    }
```
`Factory`: добавить `private val onScanFinished: () -> Unit = {}` и передать в конструктор.

- [ ] **Step 4: `MainActivity.kt:32`** — рядом с `onContributionQueued`:
```kotlin
                            onScanFinished = { ScanUploadWorker.enqueue(applicationContext) },
```
`KcalApp.onCreate`: `ScanUploadWorker.enqueue(this)` (после `SeedUpdateWorker`) — на случай, если прошлая отправка не удалась.

- [ ] **Step 5: Проверить** — `.\gradlew.bat :app:assembleDebug` и `.\gradlew.bat testDebugUnitTest` (конструктор `DiaryViewModel` менялся — юнит-тесты `:feature:diary` должны остаться зелёными). Ручная: снять этикетку, закрыть экран; через ~10 с в logcat `ScanUploadWorker: сессия scan-… отправлена`, на компьютере появился `server/data/scans/scan-…/`.

- [ ] **Step 6: Commit**

```bash
git add app feature/scanner feature/diary
git commit -m "feat(app): ScanUploadWorker отправляет сессии съёмки на сервер"
```

---

### Task 12: Документация

**Files:**
- Modify: `docs/server-contract.md` — секции `GET /v1/seed/manifest.json`, `GET /v1/seed/seed.db`, `POST /v1/scans`; заменить `GET /v1/slice/manifest.json` (План 4) ссылкой «срез брендовых товаров — отдельно, когда появится».
- Modify: `README.md` — в «Чего пока нет» убрать пункт про сервер, добавить раздел «Локальный сервер» с запуском (`cd server; .\gradlew.bat run`), `kcal.server` в `local.properties`, `build_seed.py --install`, `publish.py`; в таблицу тестов — строку «Сервер: маршруты и хранилище — `server/src/test`».
- Modify: `tools/builddb/README.md` — упомянуть копию в `server/data/seed/`.

- [ ] **Step 1: Написать** — по спеке `specs/2026-09-14-local-server-design.md`, формат ответов и заголовков брать из кода задач 5–6.

- [ ] **Step 2: Commit**

```bash
git add docs README.md tools/builddb/README.md
git commit -m "docs: локальный сервер — контракт, запуск, инструменты"
```

---

## Self-review

- **Покрытие спеки:** эндпоинты — задачи 3–6; схема — 2; `build_seed.py --install` — 5; `publish.py` — 6; адрес и cleartext — 7; `.next`/stamp'ы — 8; сеть — 9; воркеры — 10–11; документация — 12. Тест разбора манифеста на клиенте из спеки **выброшен**: `org.json` в JVM-тестах Android-модуля — заглушка и бросает; логика в три строки, покрывается ручной проверкой в задаче 10.
- **Имена сквозные:** `kcalServer(db, dataDir)`, `Db.inMemory()`, `Gtin.isValid`, `Nutriments.isPlausible`, `SeedInstaller.stage/promoteNext/installedDigest/stagedDigest/sha256`, `SeedSource`, `ScanUploader`, `ScanFrame`, `labelScansDirectory`, `serverConfigured` (переименован из `contributionsEnabled` в задаче 10 — задача 11 уже использует новое имя).
