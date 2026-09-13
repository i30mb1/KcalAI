# Kcal AI — План 1: фундамент и данные

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Рабочий офлайн-слой данных: по штрих-коду и по названию возвращается КБЖУ, записи дневника сохраняются и суммируются за день. Без UI.

**Architecture:** Два SQLite-файла с разным жизненным циклом — `seed.db` в assets (генерик-таблица + топ штрих-кодов) и докачиваемый `food.db` (полный срез Open Food Facts), объединяются через `ATTACH`. Пользовательские данные — отдельная Room-база. Оба стека работают на `BundledSQLiteDriver`, поэтому FTS5 доступен на всех устройствах независимо от системного SQLite.

**Tech Stack:** Gradle 9.5.0, AGP 9.3.2 со встроенным Kotlin 2.2.10, KSP 2.2.10-2.0.2, Room 2.8.5, androidx.sqlite 2.7.1 (bundled), JUnit 4.13.2, Python 3.14 + DuckDB для сборки базы.

**Spec:** `specs/2026-09-13-kcal-ai-design.md` (после Task 1 — `KcalAI/specs/2026-09-13-kcal-ai-design.md`)

## Global Constraints

- **Kotlin-плагин не применять.** AGP 9 включает встроенную поддержку Kotlin. Применение `org.jetbrains.kotlin.android` сломает сборку.
- **Встроенный Kotlin — 2.2.10.** Любой плагин, привязанный к версии Kotlin, должен быть парным: KSP — ровно `2.2.10-2.0.2`.
- **JAVA_HOME обязателен.** В окружении нет `java` в PATH. Все команды Gradle запускать как `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew …` (Git Bash) или выставив `$env:JAVA_HOME` в PowerShell.
- **minSdk 26, compileSdk 36 (minorApiLevel 1), targetSdk 36, Java 11** source/target compatibility.
- **Configuration cache включён** (`org.gradle.configuration-cache=true`). Код сборки должен быть configuration-cache-safe: без обращения к `Project` во время выполнения задач.
- **Единицы, ровно так:** `kcal100` — целые килокалории на 100 г. `prot100` / `fat100` / `carb100` — **сотые грамма** на 100 г (12.34 г → `1234`). `grams`, `serving_g` — целые граммы. `REAL` в схемах не использовать.
- **Namespace-префикс** всех модулей — `n7.kcalai`.
- **Исходники Kotlin кладутся в `src/main/java` и `src/test/java`** — это layout, проверенный в этом проекте.
- **Лицензия ODbL:** скрипт сборки базы и производный SQLite-срез публикуются. В любом артефакте базы таблица `meta` обязана содержать ключи `source`, `license`, `off_dump_date`.

---

### Task 1: Изолировать проект и завести git

Сейчас Kcal AI лежит в `D:\AndroidProject` вперемешку с шестью посторонними проектами (`AD2/`, `docs/`, `Kufar/`, `kotlinDL/`, `mev/`, `project_steps/`, `tiktok-scene-check/`), двумя вложенными чужими `.git` и ключами подписи `key2.jks` / `private_key.pepk`. `git init` в таком корне — прямой риск закоммитить ключи.

**Files:**
- Create: `D:\AndroidProject\KcalAI\` (каталог назначения)
- Move: `app/`, `gradle/`, `specs/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `local.properties`, `.gitignore`
- Modify: `KcalAI/.gitignore`

**Interfaces:**
- Consumes: ничего
- Produces: корень репозитория `D:\AndroidProject\KcalAI`. Все пути во всех последующих задачах — относительно него.

- [ ] **Step 1: Перенести файлы проекта в собственный каталог**

```bash
cd /d/AndroidProject
mkdir -p KcalAI
mv app gradle specs build.gradle.kts settings.gradle.kts gradle.properties \
   gradlew gradlew.bat local.properties .gitignore KcalAI/
```

`.idea/` и `.gradle/` намеренно не переносятся — они привязаны к старому корню, Android Studio создаст их заново.

- [ ] **Step 2: Убедиться, что сборка проходит с нового места**

Run:
```bash
cd /d/AndroidProject/KcalAI
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug --console=plain
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Расширить .gitignore**

Записать в `KcalAI/.gitignore` (полностью заменив содержимое):

```gitignore
# Android / Gradle
*.iml
.gradle/
/build
/app/build
/core/*/build
/feature/*/build
/build-logic/build
/captures
.externalNativeBuild
.cxx
local.properties

# IDE
/.idea/
.DS_Store

# Подпись — никогда не коммитить
*.jks
*.keystore
*.pepk

# Артефакты сборки базы
/tools/builddb/.venv/
/tools/builddb/cache/
/tools/builddb/out/
```

- [ ] **Step 4: Инициализировать репозиторий**

```bash
cd /d/AndroidProject/KcalAI
git init -b main
git add .
git commit -m "chore: изолировать Kcal AI в собственный репозиторий"
```

- [ ] **Step 5: Проверить, что секреты не попали в индекс**

Run:
```bash
cd /d/AndroidProject/KcalAI
git ls-files | grep -E '\.(jks|keystore|pepk)$|^local\.properties$' && echo "ПРОВАЛ: секреты в индексе" || echo "ОК: секретов нет"
```
Expected: `ОК: секретов нет`

Если проверка провалилась — `git rm --cached <файл>`, поправить `.gitignore`, `git commit --amend`.

---

### Task 2: Многомодульный каркас Gradle

**Files:**
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/n7.kcalai.android-library.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `core/model/build.gradle.kts`
- Create: `core/model/src/main/java/n7/kcalai/model/BuildProbe.kt`
- Create: `core/model/src/test/java/n7/kcalai/model/BuildProbeTest.kt`

**Interfaces:**
- Consumes: корень репозитория из Task 1
- Produces: convention-плагин с id `n7.kcalai.android-library`. Он задаёт `compileSdk`, `minSdk`, Java 11 и подключает JUnit в `testImplementation`. Каждый последующий модуль применяет только его и объявляет свой `namespace`.

- [ ] **Step 1: Создать included build для convention-плагинов**

`build-logic/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "build-logic"
```

`build-logic/build.gradle.kts`:

```kotlin
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.android.tools.build:gradle:9.3.2")
}
```

- [ ] **Step 2: Написать convention-плагин**

`build-logic/src/main/kotlin/n7.kcalai.android-library.gradle.kts`:

```kotlin
plugins {
    id("com.android.library")
}

android {
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    add("testImplementation", "junit:junit:4.13.2")
}
```

Kotlin-плагин здесь не применяется намеренно — его подключает `com.android.library` через встроенную поддержку AGP 9.

- [ ] **Step 3: Подключить included build и первый модуль**

Заменить в `settings.gradle.kts` блок `pluginManagement` и хвост файла:

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kcal AI"
include(":app")
include(":core:model")
```

- [ ] **Step 4: Создать модуль `:core:model`**

`core/model/build.gradle.kts`:

```kotlin
plugins {
    id("n7.kcalai.android-library")
}

android {
    namespace = "n7.kcalai.model"
}
```

`core/model/src/main/java/n7/kcalai/model/BuildProbe.kt`:

```kotlin
package n7.kcalai.model

/** Временный файл: подтверждает, что Kotlin в `src/main/java` компилируется. Удаляется в Task 3. */
internal object BuildProbe {
    const val OK: String = "ok"
}
```

`core/model/src/test/java/n7/kcalai/model/BuildProbeTest.kt`:

```kotlin
package n7.kcalai.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildProbeTest {
    @Test
    fun probe_compiles_and_runs() {
        assertEquals("ok", BuildProbe.OK)
    }
}
```

- [ ] **Step 5: Проверить, что каркас собирается и тест проходит**

Run:
```bash
cd /d/AndroidProject/KcalAI
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :core:model:testDebugUnitTest --console=plain
```
Expected: `BUILD SUCCESSFUL`, в отчёте 1 пройденный тест.

Если падает на `compileSdk { version = release(36) … }` — значит `LibraryExtension` в AGP 9.3.2 не принимает блочную форму; заменить на `compileSdk = 36` и перезапустить.

- [ ] **Step 6: Commit**

```bash
git add build-logic settings.gradle.kts core/model
git commit -m "build: многомодульный каркас с convention-плагином android-library"
```

---

### Task 3: `:core:model` — доменные типы

**Files:**
- Create: `core/model/src/main/java/n7/kcalai/model/Nutriments.kt`
- Create: `core/model/src/main/java/n7/kcalai/model/FoodRef.kt`
- Create: `core/model/src/main/java/n7/kcalai/model/MealType.kt`
- Create: `core/model/src/main/java/n7/kcalai/model/EntrySource.kt`
- Delete: `core/model/src/main/java/n7/kcalai/model/BuildProbe.kt`
- Delete: `core/model/src/test/java/n7/kcalai/model/BuildProbeTest.kt`
- Test: `core/model/src/test/java/n7/kcalai/model/NutrimentsTest.kt`

**Interfaces:**
- Consumes: модуль `:core:model` из Task 2
- Produces:
  - `data class Nutriments(kcal100: Int, prot100: Int, fat100: Int, carb100: Int)`
  - `data class NutrimentTotals(kcal: Int, protCg: Int, fatCg: Int, carbCg: Int)` — `*Cg` это сотые грамма
  - `fun Nutriments.forGrams(grams: Int): NutrimentTotals`
  - `operator fun NutrimentTotals.plus(other: NutrimentTotals): NutrimentTotals`
  - `NutrimentTotals.ZERO` — константа в companion object
  - `sealed interface FoodRef` с `FoodRef.Barcode(gtin: String)`, `FoodRef.Generic(id: Long)`, `FoodRef.User(id: Long)`
  - `enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }`
  - `enum class EntrySource { BARCODE, TEXT, MANUAL, LLM, VISION }`

- [ ] **Step 1: Написать падающий тест**

`core/model/src/test/java/n7/kcalai/model/NutrimentsTest.kt`:

```kotlin
package n7.kcalai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NutrimentsTest {

    private val buckwheat = Nutriments(kcal100 = 132, prot100 = 456, fat100 = 110, carb100 = 2500)

    @Test
    fun `сто грамм дают исходные значения`() {
        val totals = buckwheat.forGrams(100)

        assertEquals(132, totals.kcal)
        assertEquals(456, totals.protCg)
        assertEquals(110, totals.fatCg)
        assertEquals(2500, totals.carbCg)
    }

    @Test
    fun `пятьдесят грамм дают половину`() {
        val totals = buckwheat.forGrams(50)

        assertEquals(66, totals.kcal)
        assertEquals(228, totals.protCg)
        assertEquals(55, totals.fatCg)
        assertEquals(1250, totals.carbCg)
    }

    @Test
    fun `ноль грамм даёт нули`() {
        val totals = buckwheat.forGrams(0)

        assertEquals(NutrimentTotals.ZERO, totals)
    }

    @Test
    fun `дробный результат округляется вверх от половины`() {
        // 101 ккал/100 г * 50 г = 50.5 -> 51
        val totals = Nutriments(kcal100 = 101, prot100 = 0, fat100 = 0, carb100 = 0).forGrams(50)

        assertEquals(51, totals.kcal)
    }

    @Test
    fun `дробный результат округляется вниз ниже половины`() {
        // 100 ккал/100 г * 49 г = 49.0; 99 * 49 = 48.51 -> 49
        val totals = Nutriments(kcal100 = 99, prot100 = 0, fat100 = 0, carb100 = 0).forGrams(49)

        assertEquals(49, totals.kcal)
    }

    @Test
    fun `большая порция не переполняет Int`() {
        val totals = Nutriments(kcal100 = 900, prot100 = 10000, fat100 = 10000, carb100 = 10000)
            .forGrams(100_000)

        assertEquals(900_000, totals.kcal)
        assertEquals(10_000_000, totals.protCg)
    }

    @Test
    fun `отрицательная масса отвергается`() {
        assertThrows(IllegalArgumentException::class.java) {
            buckwheat.forGrams(-1)
        }
    }

    @Test
    fun `суммы складываются`() {
        val a = buckwheat.forGrams(100)
        val b = buckwheat.forGrams(50)

        val sum = a + b

        assertEquals(198, sum.kcal)
        assertEquals(684, sum.protCg)
    }

    @Test
    fun `ноль нейтрален при сложении`() {
        val a = buckwheat.forGrams(100)

        assertEquals(a, a + NutrimentTotals.ZERO)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :core:model:testDebugUnitTest --console=plain
```
Expected: FAIL — ошибки компиляции `Unresolved reference: Nutriments`, `NutrimentTotals`, `forGrams`.

- [ ] **Step 3: Реализовать минимально**

`core/model/src/main/java/n7/kcalai/model/Nutriments.kt`:

```kotlin
package n7.kcalai.model

/**
 * КБЖУ на 100 г продукта в фиксированной точке.
 *
 * @param kcal100 целые килокалории на 100 г
 * @param prot100 белки, сотые грамма на 100 г (12.34 г -> 1234)
 * @param fat100  жиры, сотые грамма на 100 г
 * @param carb100 углеводы, сотые грамма на 100 г
 */
data class Nutriments(
    val kcal100: Int,
    val prot100: Int,
    val fat100: Int,
    val carb100: Int,
)

/**
 * Абсолютные значения для конкретной порции.
 *
 * @param kcal   целые килокалории
 * @param protCg белки, сотые грамма
 * @param fatCg  жиры, сотые грамма
 * @param carbCg углеводы, сотые грамма
 */
data class NutrimentTotals(
    val kcal: Int,
    val protCg: Int,
    val fatCg: Int,
    val carbCg: Int,
) {
    companion object {
        val ZERO: NutrimentTotals = NutrimentTotals(0, 0, 0, 0)
    }
}

/** Пересчитывает значения «на 100 г» в значения для [grams] грамм. */
fun Nutriments.forGrams(grams: Int): NutrimentTotals {
    require(grams >= 0) { "grams must be >= 0, was $grams" }
    return NutrimentTotals(
        kcal = scale(kcal100, grams),
        protCg = scale(prot100, grams),
        fatCg = scale(fat100, grams),
        carbCg = scale(carb100, grams),
    )
}

operator fun NutrimentTotals.plus(other: NutrimentTotals): NutrimentTotals =
    NutrimentTotals(
        kcal = kcal + other.kcal,
        protCg = protCg + other.protCg,
        fatCg = fatCg + other.fatCg,
        carbCg = carbCg + other.carbCg,
    )

/** Умножение в Long, чтобы большие порции не переполняли Int; округление к ближайшему. */
private fun scale(per100: Int, grams: Int): Int {
    val product = per100.toLong() * grams.toLong()
    return ((product + 50L) / 100L).toInt()
}
```

`core/model/src/main/java/n7/kcalai/model/FoodRef.kt`:

```kotlin
package n7.kcalai.model

/** Ссылка на продукт в одном из трёх источников. */
sealed interface FoodRef {

    /** Товар со штрих-кодом из `product` или `product_seed`. */
    data class Barcode(val gtin: String) : FoodRef

    /** Позиция генерик-таблицы («гречка отварная»). */
    data class Generic(val id: Long) : FoodRef

    /** Продукт, заведённый самим пользователем. */
    data class User(val id: Long) : FoodRef
}
```

`core/model/src/main/java/n7/kcalai/model/MealType.kt`:

```kotlin
package n7.kcalai.model

enum class MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK,
}
```

`core/model/src/main/java/n7/kcalai/model/EntrySource.kt`:

```kotlin
package n7.kcalai.model

/** Чем именно была добавлена запись — нужно для диагностики качества распознавания. */
enum class EntrySource {
    BARCODE,
    TEXT,
    MANUAL,
    LLM,
    VISION,
}
```

- [ ] **Step 4: Удалить временный пробник**

```bash
rm core/model/src/main/java/n7/kcalai/model/BuildProbe.kt
rm core/model/src/test/java/n7/kcalai/model/BuildProbeTest.kt
```

- [ ] **Step 5: Запустить тесты и убедиться, что они проходят**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :core:model:testDebugUnitTest --console=plain
```
Expected: `BUILD SUCCESSFUL`, 9 пройденных тестов.

- [ ] **Step 6: Commit**

```bash
git add core/model
git commit -m "feat(model): доменные типы КБЖУ с фиксированной точкой"
```

---

### Task 4: `tools/builddb` — схема и генерик-таблица

Собирает `seed.db` из версионируемых CSV. DuckDB здесь не нужен — хватает `sqlite3` из стандартной библиотеки Python. Таблицы `product_seed` создаются пустыми, наполняет их Task 5.

**Files:**
- Create: `tools/builddb/schema_seed.sql`
- Create: `tools/builddb/data/generic.csv`
- Create: `tools/builddb/data/generic_alias.csv`
- Create: `tools/builddb/data/portion_unit.csv`
- Create: `tools/builddb/build_seed.py`
- Create: `tools/builddb/README.md`
- Test: `tools/builddb/test_build_seed.py`

**Interfaces:**
- Consumes: ничего из предыдущих задач
- Produces:
  - `tools/builddb/out/seed.db` — SQLite с таблицами `generic`, `generic_alias`, `generic_fts`, `portion_unit`, `product_seed`, `product_seed_fts`, `meta`
  - `build_seed.py` предоставляет `build_seed(out_path: Path, data_dir: Path) -> None` и `verify_seed(db_path: Path) -> list[str]` (возвращает список нарушенных инвариантов, пустой список = всё хорошо)

- [ ] **Step 1: Написать схему**

`tools/builddb/schema_seed.sql`:

```sql
CREATE TABLE generic (
    id                INTEGER PRIMARY KEY,
    name_key          TEXT    NOT NULL UNIQUE,
    kcal100           INTEGER NOT NULL,
    prot100           INTEGER NOT NULL,
    fat100            INTEGER NOT NULL,
    carb100           INTEGER NOT NULL,
    default_portion_g INTEGER NOT NULL   -- типичная разовая порция, граммы
);

CREATE TABLE generic_alias (
    generic_id INTEGER NOT NULL REFERENCES generic(id),
    alias      TEXT    NOT NULL,
    lang       TEXT    NOT NULL,
    PRIMARY KEY (generic_id, alias, lang)
);

CREATE VIRTUAL TABLE generic_fts USING fts5(
    alias,
    generic_id UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TABLE portion_unit (
    generic_id INTEGER NOT NULL REFERENCES generic(id),
    unit       TEXT    NOT NULL,
    grams      INTEGER NOT NULL,
    PRIMARY KEY (generic_id, unit)
);

CREATE TABLE product_seed (
    barcode    TEXT    PRIMARY KEY,
    name       TEXT    NOT NULL,
    brand      TEXT,
    kcal100    INTEGER NOT NULL,
    prot100    INTEGER NOT NULL,
    fat100     INTEGER NOT NULL,
    carb100    INTEGER NOT NULL,
    serving_g  INTEGER,
    popularity INTEGER NOT NULL DEFAULT 0
);

CREATE VIRTUAL TABLE product_seed_fts USING fts5(
    name,
    brand,
    barcode UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
```

- [ ] **Step 2: Завести стартовые данные**

`tools/builddb/data/generic.csv`:

```csv
id,name_key,kcal100,prot100,fat100,carb100,default_portion_g
1,buckwheat_boiled,110,410,110,2130,150
2,chicken_breast_raw,113,2310,180,0,120
3,egg_chicken_raw,157,1270,1150,70,60
4,butter_82,748,50,8250,80,10
5,rice_white_boiled,116,220,20,2500,150
6,oats_boiled,88,300,170,1500,200
7,cottage_cheese_5,121,1700,500,180,150
8,banana,95,150,20,2130,120
9,apple,47,40,40,980,180
10,olive_oil,898,0,9980,0,10
```

`default_portion_g` — типичная разовая порция. Она нужна, когда человек пишет «немного
гречки» без всякого веса: вес взять неоткуда, и единственный честный ответ — предложить
обычную порцию и дать поправить. Расплывчатые модификаторы становятся множителями к ней
(«немного» ×0.5, без модификатора ×1, «много» ×1.5). Это данные, а не логика — сама
подстановка живёт в Плане 2.

`tools/builddb/data/generic_alias.csv`:

```csv
generic_id,alias,lang
1,гречка,ru
1,гречневая каша,ru
1,гречка отварная,ru
1,buckwheat,en
2,куриная грудка,ru
2,курица,ru
2,chicken breast,en
3,яйцо,ru
3,яйца,ru
3,egg,en
4,масло сливочное,ru
4,сливочное масло,ru
4,butter,en
5,рис,ru
5,рис отварной,ru
5,rice,en
6,овсянка,ru
6,овсяная каша,ru
6,oatmeal,en
7,творог,ru
7,cottage cheese,en
8,банан,ru
8,banana,en
9,яблоко,ru
9,apple,en
10,оливковое масло,ru
10,olive oil,en
```

`tools/builddb/data/portion_unit.csv`:

```csv
generic_id,unit,grams
3,шт,60
8,шт,120
9,шт,180
4,ст.л.,17
4,ч.л.,6
10,ст.л.,17
10,ч.л.,5
1,стакан,200
5,стакан,200
```

`tools/builddb/README.md`:

````markdown
# builddb

Сборка справочных баз Kcal AI.

- `build_seed.py` — собирает `out/seed.db` из CSV в `data/`. Только стандартная библиотека.
- `build_food.py` — извлекает полный срез Open Food Facts в `out/food.db`, наполняет
  `product_seed` в `seed.db` и упаковывает срез с манифестом. Требует DuckDB и zstandard
  (см. `requirements.txt`).

## Запуск

```
python build_seed.py
python -c "from pathlib import Path; import build_seed; print(build_seed.verify_seed(Path('out/seed.db')))"
```

## Лицензия данных

Данные о товарах со штрих-кодами происходят из Open Food Facts и распространяются
под ODbL. Производный срез и эти скрипты публикуются на тех же условиях.
Генерик-таблица в `data/` составлена вручную и к OFF отношения не имеет.
````

- [ ] **Step 3: Написать падающий тест**

`tools/builddb/test_build_seed.py`:

```python
import sqlite3
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

import build_seed

DATA_DIR = Path(__file__).parent / "data"


class BuildSeedTest(unittest.TestCase):

    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.out = Path(self._tmp.name) / "seed.db"
        build_seed.build_seed(self.out, DATA_DIR)
        self.conn = sqlite3.connect(self.out)

    def tearDown(self):
        self.conn.close()
        self._tmp.cleanup()

    def test_generic_rows_loaded(self):
        count = self.conn.execute("SELECT count(*) FROM generic").fetchone()[0]
        self.assertEqual(10, count)

    def test_aliases_loaded(self):
        count = self.conn.execute("SELECT count(*) FROM generic_alias").fetchone()[0]
        self.assertEqual(27, count)

    def test_fts_finds_russian_alias(self):
        rows = self.conn.execute(
            "SELECT generic_id FROM generic_fts WHERE generic_fts MATCH ?", ("гречка",)
        ).fetchall()
        self.assertIn((1,), rows)

    def test_fts_finds_english_alias(self):
        rows = self.conn.execute(
            "SELECT generic_id FROM generic_fts WHERE generic_fts MATCH ?", ("buckwheat",)
        ).fetchall()
        self.assertIn((1,), rows)

    def test_default_portion_loaded(self):
        grams = self.conn.execute(
            "SELECT default_portion_g FROM generic WHERE name_key = 'buckwheat_boiled'"
        ).fetchone()[0]
        self.assertEqual(150, grams)

    def test_every_generic_has_a_usable_default_portion(self):
        bad = self.conn.execute(
            "SELECT count(*) FROM generic WHERE default_portion_g < 1 OR default_portion_g > 2000"
        ).fetchone()[0]
        self.assertEqual(0, bad)

    def test_verify_catches_absurd_default_portion(self):
        self.conn.execute("UPDATE generic SET default_portion_g = 9000 WHERE id = 1")
        self.conn.commit()
        problems = build_seed.verify_seed(self.out)
        self.assertTrue(any("default_portion_g" in p for p in problems), problems)

    def test_portion_unit_egg_is_60g(self):
        grams = self.conn.execute(
            "SELECT grams FROM portion_unit WHERE generic_id = 3 AND unit = 'шт'"
        ).fetchone()[0]
        self.assertEqual(60, grams)

    def test_product_seed_created_but_empty(self):
        count = self.conn.execute("SELECT count(*) FROM product_seed").fetchone()[0]
        self.assertEqual(0, count)

    def test_meta_carries_license(self):
        meta = dict(self.conn.execute("SELECT key, value FROM meta").fetchall())
        self.assertIn("license", meta)
        self.assertIn("source", meta)
        self.assertIn("schema_version", meta)

    def test_verify_passes_on_fresh_build(self):
        self.assertEqual([], build_seed.verify_seed(self.out))

    def test_verify_catches_orphan_alias(self):
        self.conn.execute("INSERT INTO generic_alias VALUES (999, 'призрак', 'ru')")
        self.conn.commit()
        problems = build_seed.verify_seed(self.out)
        self.assertTrue(any("generic_alias" in p for p in problems), problems)

    def test_verify_catches_absurd_kcal(self):
        self.conn.execute("UPDATE generic SET kcal100 = 5000 WHERE id = 1")
        self.conn.commit()
        problems = build_seed.verify_seed(self.out)
        self.assertTrue(any("kcal100" in p for p in problems), problems)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 4: Запустить тест и убедиться, что он падает**

Run:
```bash
cd tools/builddb
python -m unittest test_build_seed -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'build_seed'`

- [ ] **Step 5: Реализовать сборщик**

`tools/builddb/build_seed.py`:

```python
"""Сборка seed.db — справочника, который едет в assets приложения.

Только стандартная библиотека: скрипт должен запускаться без установки зависимостей.
"""

from __future__ import annotations

import csv
import sqlite3
from datetime import date
from pathlib import Path

SCHEMA_VERSION = "1"
HERE = Path(__file__).parent

KCAL_MAX = 900          # ккал на 100 г; выше физически невозможно
MACRO_MAX_CG = 10_000   # 100.00 г на 100 г


def build_seed(out_path: Path, data_dir: Path) -> None:
    """Создаёт seed.db с нуля. Существующий файл перезаписывается."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    if out_path.exists():
        out_path.unlink()

    schema = (HERE / "schema_seed.sql").read_text(encoding="utf-8")

    with sqlite3.connect(out_path) as conn:
        conn.executescript(schema)
        _load_generic(conn, data_dir / "generic.csv")
        _load_aliases(conn, data_dir / "generic_alias.csv")
        _load_portion_units(conn, data_dir / "portion_unit.csv")
        _fill_meta(conn)
        conn.commit()
        conn.execute("VACUUM")


def _rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


def _load_generic(conn: sqlite3.Connection, path: Path) -> None:
    conn.executemany(
        "INSERT INTO generic (id, name_key, kcal100, prot100, fat100, carb100, default_portion_g) "
        "VALUES (?, ?, ?, ?, ?, ?, ?)",
        [
            (
                int(r["id"]),
                r["name_key"],
                int(r["kcal100"]),
                int(r["prot100"]),
                int(r["fat100"]),
                int(r["carb100"]),
                int(r["default_portion_g"]),
            )
            for r in _rows(path)
        ],
    )


def _load_aliases(conn: sqlite3.Connection, path: Path) -> None:
    rows = [(int(r["generic_id"]), r["alias"].strip(), r["lang"]) for r in _rows(path)]
    conn.executemany(
        "INSERT INTO generic_alias (generic_id, alias, lang) VALUES (?, ?, ?)", rows
    )
    conn.executemany(
        "INSERT INTO generic_fts (alias, generic_id) VALUES (?, ?)",
        [(alias, gid) for gid, alias, _lang in rows],
    )


def _load_portion_units(conn: sqlite3.Connection, path: Path) -> None:
    conn.executemany(
        "INSERT INTO portion_unit (generic_id, unit, grams) VALUES (?, ?, ?)",
        [
            (int(r["generic_id"]), r["unit"].strip(), int(r["grams"]))
            for r in _rows(path)
        ],
    )


def _fill_meta(conn: sqlite3.Connection) -> None:
    conn.executemany(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        [
            ("schema_version", SCHEMA_VERSION),
            ("built_at", date.today().isoformat()),
            ("source", "Open Food Facts (product_seed) + curated generic table"),
            ("license", "ODbL-1.0"),
            ("off_dump_date", ""),
        ],
    )


def verify_seed(db_path: Path) -> list[str]:
    """Возвращает список нарушенных инвариантов. Пустой список означает, что база здорова."""
    problems: list[str] = []
    with sqlite3.connect(db_path) as conn:
        orphans = conn.execute(
            "SELECT count(*) FROM generic_alias a "
            "LEFT JOIN generic g ON g.id = a.generic_id WHERE g.id IS NULL"
        ).fetchone()[0]
        if orphans:
            problems.append(f"generic_alias: {orphans} записей ссылаются на несуществующий generic")

        orphan_units = conn.execute(
            "SELECT count(*) FROM portion_unit u "
            "LEFT JOIN generic g ON g.id = u.generic_id WHERE g.id IS NULL"
        ).fetchone()[0]
        if orphan_units:
            problems.append(f"portion_unit: {orphan_units} записей ссылаются на несуществующий generic")

        bad_kcal = conn.execute(
            "SELECT count(*) FROM generic WHERE kcal100 < 0 OR kcal100 > ?", (KCAL_MAX,)
        ).fetchone()[0]
        if bad_kcal:
            problems.append(f"generic.kcal100: {bad_kcal} значений вне диапазона 0..{KCAL_MAX}")

        for column in ("prot100", "fat100", "carb100"):
            bad = conn.execute(
                f"SELECT count(*) FROM generic WHERE {column} < 0 OR {column} > ?",
                (MACRO_MAX_CG,),
            ).fetchone()[0]
            if bad:
                problems.append(f"generic.{column}: {bad} значений вне диапазона 0..{MACRO_MAX_CG}")

        bad_portion = conn.execute(
            "SELECT count(*) FROM generic WHERE default_portion_g < 1 OR default_portion_g > 2000"
        ).fetchone()[0]
        if bad_portion:
            problems.append(
                f"generic.default_portion_g: {bad_portion} значений вне диапазона 1..2000"
            )

        no_alias = conn.execute(
            "SELECT count(*) FROM generic g "
            "WHERE NOT EXISTS (SELECT 1 FROM generic_alias a WHERE a.generic_id = g.id)"
        ).fetchone()[0]
        if no_alias:
            problems.append(f"generic: {no_alias} позиций без единого алиаса — их нельзя найти поиском")

        fts_count = conn.execute("SELECT count(*) FROM generic_fts").fetchone()[0]
        alias_count = conn.execute("SELECT count(*) FROM generic_alias").fetchone()[0]
        if fts_count != alias_count:
            problems.append(f"generic_fts: {fts_count} строк против {alias_count} алиасов")

    return problems


if __name__ == "__main__":
    out = HERE / "out" / "seed.db"
    build_seed(out, HERE / "data")
    found = verify_seed(out)
    if found:
        for problem in found:
            print(f"ПРОВАЛ: {problem}")
        raise SystemExit(1)
    print(f"ОК: {out}")
```

- [ ] **Step 6: Запустить тесты и убедиться, что они проходят**

Run:
```bash
cd tools/builddb
python -m unittest test_build_seed -v
```
Expected: `OK`, 13 пройденных тестов.

Если `test_fts_finds_russian_alias` падает — значит в сборке Python нет FTS5. Проверить: `python -c "import sqlite3; sqlite3.connect(':memory:').execute('CREATE VIRTUAL TABLE t USING fts5(x)')"`. На Android это неважно (там свой bundled SQLite), но для сборки нужен Python с FTS5.

- [ ] **Step 7: Commit**

```bash
cd /d/AndroidProject/KcalAI
git add tools/builddb
git commit -m "feat(builddb): сборка seed.db из генерик-таблицы с проверкой инвариантов"
```

---

### Task 5: `tools/builddb` — извлечение Open Food Facts

**Files:**
- Create: `tools/builddb/requirements.txt`
- Create: `tools/builddb/build_food.py`
- Create: `tools/builddb/fixtures/off_sample.parquet` (генерируется на шаге 2)
- Test: `tools/builddb/test_build_food.py`

**Interfaces:**
- Consumes: `build_seed.build_seed` из Task 4
- Produces:
  - `build_food.extract(parquet_url_or_path: str, out_dir: Path) -> Path` — создаёт `out/food.db` и возвращает путь
  - `build_food.fill_product_seed(seed_db: Path, food_db: Path, limit: int) -> int` — переносит top-N по `popularity` в `product_seed` и возвращает число строк
  - `build_food.verify_food(db_path: Path) -> list[str]`
  - `build_food.package_food(food_db: Path, version: str) -> Path` — сжимает базу в `food-<version>.db.zst`, пишет рядом `manifest.json` с `version`, `sha256`, `size_compressed`, `size_raw`, `row_count`, `off_dump_date`, `license`; возвращает путь к манифесту

Схема дампа (проверена по HF datasets-server, сентябрь 2026): `code` — строка; `product_name` — список структур `{lang, text}`; `nutriments` — список структур `{name, value, "100g", serving, unit, …}`; `serving_quantity` — **строка**; `unique_scans_n` — int32; `countries_tags` — список строк.

- [ ] **Step 1: Зафиксировать зависимость**

`tools/builddb/requirements.txt`:

```
duckdb>=1.1,<2
zstandard>=0.22,<1
```

Установка:
```bash
cd tools/builddb
python -m pip install -r requirements.txt
```

- [ ] **Step 2: Сгенерировать фикстуру, повторяющую схему дампа**

Создать `tools/builddb/fixtures/make_fixture.py`:

```python
"""Генерирует маленький parquet с той же структурой, что дамп Open Food Facts."""

from pathlib import Path

import duckdb

HERE = Path(__file__).parent


def main() -> None:
    con = duckdb.connect()
    con.execute(
        """
        CREATE TABLE sample AS
        SELECT * FROM (VALUES
            ('3017620422003',
             [{'lang': 'main', 'text': 'Nutella'}],
             'Ferrero',
             [{'name': 'energy-kcal', '100g': 539.0},
              {'name': 'proteins',     '100g': 6.3},
              {'name': 'fat',          '100g': 30.9},
              {'name': 'carbohydrates','100g': 57.5}],
             '15', 120000),
            ('4600699500001',
             [{'lang': 'main', 'text': 'Гречка ядрица'}],
             'Мистраль',
             [{'name': 'energy-kcal', '100g': 308.0},
              {'name': 'proteins',     '100g': 12.6},
              {'name': 'fat',          '100g': 3.3},
              {'name': 'carbohydrates','100g': 57.1}],
             '100', 800),
            ('0000000000001',
             [{'lang': 'main', 'text': 'Мусор без КБЖУ'}],
             NULL,
             [{'name': 'proteins', '100g': 1.0}],
             NULL, 5),
            ('0000000000002',
             [{'lang': 'main', 'text': 'Невозможные калории'}],
             NULL,
             [{'name': 'energy-kcal', '100g': 5000.0},
              {'name': 'proteins',     '100g': 1.0},
              {'name': 'fat',          '100g': 1.0},
              {'name': 'carbohydrates','100g': 1.0}],
             NULL, 7),
            ('3017620422003',
             [{'lang': 'main', 'text': 'Nutella дубль победнее'}],
             'Ferrero',
             [{'name': 'energy-kcal', '100g': 539.0},
              {'name': 'proteins',     '100g': 6.3},
              {'name': 'fat',          '100g': 30.9},
              {'name': 'carbohydrates','100g': 57.5}],
             NULL, 3)
        ) AS t(code, product_name, brands, nutriments, serving_quantity, unique_scans_n)
        """
    )
    out = HERE / "off_sample.parquet"
    con.execute(f"COPY sample TO '{out.as_posix()}' (FORMAT PARQUET)")
    print(f"ОК: {out}")


if __name__ == "__main__":
    main()
```

Run:
```bash
cd tools/builddb
python fixtures/make_fixture.py
```
Expected: `ОК: .../off_sample.parquet`

- [ ] **Step 3: Написать падающий тест**

`tools/builddb/test_build_food.py`:

```python
import sqlite3
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

import build_food
import build_seed

HERE = Path(__file__).parent
FIXTURE = HERE / "fixtures" / "off_sample.parquet"


class BuildFoodTest(unittest.TestCase):

    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.out_dir = Path(self._tmp.name)
        self.food_db = build_food.extract(str(FIXTURE), self.out_dir)
        self.conn = sqlite3.connect(self.food_db)

    def tearDown(self):
        self.conn.close()
        self._tmp.cleanup()

    def _codes(self):
        return {r[0] for r in self.conn.execute("SELECT barcode FROM product").fetchall()}

    def test_valid_products_kept(self):
        self.assertIn("3017620422003", self._codes())
        self.assertIn("4600699500001", self._codes())

    def test_product_without_full_nutriments_dropped(self):
        self.assertNotIn("0000000000001", self._codes())

    def test_absurd_kcal_dropped(self):
        self.assertNotIn("0000000000002", self._codes())

    def test_duplicate_barcode_deduped_keeping_most_popular(self):
        rows = self.conn.execute(
            "SELECT name, popularity FROM product WHERE barcode = '3017620422003'"
        ).fetchall()
        self.assertEqual(1, len(rows))
        self.assertEqual("Nutella", rows[0][0])
        self.assertEqual(120000, rows[0][1])

    def test_fixed_point_conversion(self):
        row = self.conn.execute(
            "SELECT kcal100, prot100, fat100, carb100 FROM product WHERE barcode = '3017620422003'"
        ).fetchone()
        self.assertEqual((539, 630, 3090, 5750), row)

    def test_serving_quantity_string_parsed_to_int(self):
        serving = self.conn.execute(
            "SELECT serving_g FROM product WHERE barcode = '3017620422003'"
        ).fetchone()[0]
        self.assertEqual(15, serving)

    def test_fts_populated(self):
        rows = self.conn.execute(
            "SELECT barcode FROM product_fts WHERE product_fts MATCH ?", ("Гречка",)
        ).fetchall()
        self.assertIn(("4600699500001",), rows)

    def test_meta_records_license(self):
        meta = dict(self.conn.execute("SELECT key, value FROM meta").fetchall())
        self.assertEqual("ODbL-1.0", meta["license"])
        self.assertIn("Open Food Facts", meta["source"])

    def test_verify_passes(self):
        self.assertEqual([], build_food.verify_food(self.food_db))

    def test_package_writes_archive_and_manifest(self):
        import hashlib
        import json

        manifest_path = build_food.package_food(self.food_db, version="20260913")

        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        archive = manifest_path.parent / "food-20260913.db.zst"

        self.assertTrue(archive.exists())
        self.assertEqual("20260913", manifest["version"])
        self.assertEqual("ODbL-1.0", manifest["license"])
        self.assertEqual(archive.stat().st_size, manifest["size_compressed"])
        self.assertEqual(self.food_db.stat().st_size, manifest["size_raw"])
        self.assertEqual(2, manifest["row_count"])

        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        self.assertEqual(digest, manifest["sha256"])

    def test_packaged_archive_decompresses_to_identical_bytes(self):
        import zstandard

        manifest_path = build_food.package_food(self.food_db, version="20260913")
        archive = manifest_path.parent / "food-20260913.db.zst"

        raw = zstandard.ZstdDecompressor().decompress(
            archive.read_bytes(), max_output_size=100 * 1024 * 1024
        )

        self.assertEqual(self.food_db.read_bytes(), raw)

    def test_fill_product_seed_takes_top_n(self):
        seed = self.out_dir / "seed.db"
        build_seed.build_seed(seed, HERE / "data")

        moved = build_food.fill_product_seed(seed, self.food_db, limit=1)

        self.assertEqual(1, moved)
        with sqlite3.connect(seed) as conn:
            rows = conn.execute("SELECT barcode FROM product_seed").fetchall()
            fts = conn.execute(
                "SELECT barcode FROM product_seed_fts WHERE product_seed_fts MATCH ?",
                ("Nutella",),
            ).fetchall()
        self.assertEqual([("3017620422003",)], rows)
        self.assertEqual([("3017620422003",)], fts)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 4: Запустить тест и убедиться, что он падает**

Run:
```bash
cd tools/builddb
python -m unittest test_build_food -v
```
Expected: FAIL — `ModuleNotFoundError: No module named 'build_food'`

- [ ] **Step 5: Реализовать извлечение**

`tools/builddb/build_food.py`:

```python
"""Извлечение среза Open Food Facts в SQLite.

Источник — Parquet-дамп OFF. Читается напрямую по URL (DuckDB тянет только нужные
колонки через HTTP range-запросы) либо из локального файла.
"""

from __future__ import annotations

import sqlite3
from datetime import date
from pathlib import Path

import duckdb

KCAL_MAX = 900
MACRO_MAX = 100.0

SCHEMA_FOOD = """
CREATE TABLE product (
    barcode    TEXT    PRIMARY KEY,
    name       TEXT    NOT NULL,
    brand      TEXT,
    kcal100    INTEGER NOT NULL,
    prot100    INTEGER NOT NULL,
    fat100     INTEGER NOT NULL,
    carb100    INTEGER NOT NULL,
    serving_g  INTEGER,
    popularity INTEGER NOT NULL DEFAULT 0
);

CREATE VIRTUAL TABLE product_fts USING fts5(
    name,
    brand,
    barcode UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""

# Достаёт "100g" нужного нутриента из списка структур nutriments.
_NUTRIENT = "(list_filter(nutriments, x -> x.name = '{name}')[1])['100g']"

# DuckDB не принимает несколько инструкций в одном execute() с параметрами,
# поэтому три шага выполняются отдельными вызовами, а параметр есть только у первого.
_SQL_STAGED = f"""
CREATE OR REPLACE TABLE staged AS
SELECT
    regexp_replace(code, '[^0-9]', '', 'g') AS barcode,
    trim(COALESCE(
        (list_filter(product_name, x -> x.lang = 'main' AND x.text <> '')[1]).text,
        (list_filter(product_name, x -> x.text <> '')[1]).text
    )) AS name,
    nullif(trim(COALESCE(brands, '')), '') AS brand,
    {_NUTRIENT.format(name='energy-kcal')}   AS kcal,
    {_NUTRIENT.format(name='proteins')}      AS prot,
    {_NUTRIENT.format(name='fat')}           AS fat,
    {_NUTRIENT.format(name='carbohydrates')} AS carb,
    TRY_CAST(serving_quantity AS DOUBLE)     AS serving,
    COALESCE(unique_scans_n, 0)              AS popularity
FROM read_parquet(?);
"""

_SQL_CLEAN = f"""
CREATE OR REPLACE TABLE clean AS
SELECT
    barcode,
    name,
    brand,
    CAST(round(kcal)       AS INTEGER) AS kcal100,
    CAST(round(prot * 100) AS INTEGER) AS prot100,
    CAST(round(fat  * 100) AS INTEGER) AS fat100,
    CAST(round(carb * 100) AS INTEGER) AS carb100,
    CAST(round(serving)    AS INTEGER) AS serving_g,
    popularity
FROM staged
WHERE name IS NOT NULL AND name <> ''
  AND kcal IS NOT NULL AND prot IS NOT NULL AND fat IS NOT NULL AND carb IS NOT NULL
  AND kcal BETWEEN 0 AND {KCAL_MAX}
  AND prot BETWEEN 0 AND {MACRO_MAX}
  AND fat  BETWEEN 0 AND {MACRO_MAX}
  AND carb BETWEEN 0 AND {MACRO_MAX}
  AND NOT (kcal = 0 AND prot = 0 AND fat = 0 AND carb = 0)
  AND length(barcode) BETWEEN 8 AND 14;
"""

_SQL_DEDUPED = """
CREATE OR REPLACE TABLE deduped AS
SELECT barcode, name, brand, kcal100, prot100, fat100, carb100, serving_g, popularity
FROM (
    SELECT *, row_number() OVER (
        PARTITION BY barcode
        ORDER BY popularity DESC, (serving_g IS NOT NULL) DESC, length(name) DESC
    ) AS rn
    FROM clean
)
WHERE rn = 1;
"""


def extract(parquet_source: str, out_dir: Path) -> Path:
    """Строит out_dir/food.db из Parquet-дампа OFF. Возвращает путь к базе."""
    out_dir.mkdir(parents=True, exist_ok=True)
    food_db = out_dir / "food.db"
    if food_db.exists():
        food_db.unlink()

    con = duckdb.connect()
    con.execute("INSTALL httpfs")
    con.execute("LOAD httpfs")
    con.execute(_SQL_STAGED, [parquet_source])
    con.execute(_SQL_CLEAN)
    con.execute(_SQL_DEDUPED)
    rows = con.execute(
        "SELECT barcode, name, brand, kcal100, prot100, fat100, carb100, serving_g, popularity "
        "FROM deduped"
    ).fetchall()
    con.close()

    with sqlite3.connect(food_db) as sq:
        sq.executescript(SCHEMA_FOOD)
        sq.executemany(
            "INSERT INTO product (barcode, name, brand, kcal100, prot100, fat100, "
            "carb100, serving_g, popularity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            rows,
        )
        sq.executemany(
            "INSERT INTO product_fts (name, brand, barcode) VALUES (?, ?, ?)",
            [(r[1], r[2], r[0]) for r in rows],
        )
        sq.executemany(
            "INSERT INTO meta (key, value) VALUES (?, ?)",
            [
                ("schema_version", "1"),
                ("built_at", date.today().isoformat()),
                ("source", "Open Food Facts"),
                ("license", "ODbL-1.0"),
                ("off_dump_date", date.today().isoformat()),
                ("row_count", str(len(rows))),
            ],
        )
        sq.commit()
        sq.execute("VACUUM")

    return food_db


def fill_product_seed(seed_db: Path, food_db: Path, limit: int) -> int:
    """Копирует top-N по популярности из food.db в product_seed внутри seed.db."""
    with sqlite3.connect(food_db) as src:
        rows = src.execute(
            "SELECT barcode, name, brand, kcal100, prot100, fat100, carb100, serving_g, popularity "
            "FROM product ORDER BY popularity DESC, barcode LIMIT ?",
            (limit,),
        ).fetchall()

    with sqlite3.connect(seed_db) as dst:
        dst.execute("DELETE FROM product_seed")
        dst.execute("DELETE FROM product_seed_fts")
        dst.executemany(
            "INSERT INTO product_seed (barcode, name, brand, kcal100, prot100, fat100, "
            "carb100, serving_g, popularity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            rows,
        )
        dst.executemany(
            "INSERT INTO product_seed_fts (name, brand, barcode) VALUES (?, ?, ?)",
            [(r[1], r[2], r[0]) for r in rows],
        )
        dst.commit()
        dst.execute("VACUUM")

    return len(rows)


def package_food(food_db: Path, version: str) -> Path:
    """Сжимает food.db в food-<version>.db.zst и пишет рядом manifest.json.

    Манифест — то, по чему приложение решает, нужна ли докачка, и чем проверяет
    целостность скачанного файла.
    """
    import hashlib
    import json

    import zstandard

    archive = food_db.parent / f"food-{version}.db.zst"
    raw = food_db.read_bytes()
    archive.write_bytes(zstandard.ZstdCompressor(level=19).compress(raw))

    with sqlite3.connect(food_db) as conn:
        meta = dict(conn.execute("SELECT key, value FROM meta").fetchall())
        row_count = conn.execute("SELECT count(*) FROM product").fetchone()[0]

    manifest = {
        "version": version,
        "file": archive.name,
        "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
        "size_compressed": archive.stat().st_size,
        "size_raw": len(raw),
        "row_count": row_count,
        "off_dump_date": meta.get("off_dump_date", ""),
        "source": meta.get("source", ""),
        "license": meta.get("license", ""),
    }

    manifest_path = food_db.parent / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return manifest_path


def verify_food(db_path: Path) -> list[str]:
    """Возвращает список нарушенных инвариантов. Пустой список означает, что база здорова."""
    problems: list[str] = []
    with sqlite3.connect(db_path) as conn:
        total = conn.execute("SELECT count(*) FROM product").fetchone()[0]
        if total == 0:
            problems.append("product: таблица пуста")

        bad_kcal = conn.execute(
            "SELECT count(*) FROM product WHERE kcal100 < 0 OR kcal100 > ?", (KCAL_MAX,)
        ).fetchone()[0]
        if bad_kcal:
            problems.append(f"product.kcal100: {bad_kcal} значений вне диапазона 0..{KCAL_MAX}")

        empty_name = conn.execute(
            "SELECT count(*) FROM product WHERE name IS NULL OR trim(name) = ''"
        ).fetchone()[0]
        if empty_name:
            problems.append(f"product.name: {empty_name} пустых названий")

        fts_count = conn.execute("SELECT count(*) FROM product_fts").fetchone()[0]
        if fts_count != total:
            problems.append(f"product_fts: {fts_count} строк против {total} товаров")

        meta = dict(conn.execute("SELECT key, value FROM meta").fetchall())
        for required in ("source", "license", "off_dump_date"):
            if not meta.get(required):
                problems.append(f"meta.{required}: отсутствует (нарушение обязательств ODbL)")

    return problems
```

- [ ] **Step 6: Запустить тесты и убедиться, что они проходят**

Run:
```bash
cd tools/builddb
python -m unittest test_build_food -v
```
Expected: `OK`, 12 пройденных тестов.

- [ ] **Step 7: Найти точное имя файла в дампе OFF и выполнить боевое извлечение**

Имя parquet-файла в датасете нужно узнать, а не угадывать:

```bash
python -c "import json,urllib.request; d=json.load(urllib.request.urlopen('https://huggingface.co/api/datasets/openfoodfacts/product-database')); print([s['rfilename'] for s in d['siblings'] if s['rfilename'].endswith('.parquet')])"
```

Затем, подставив найденное имя `<FILE>`:

```bash
cd tools/builddb
python -c "
from datetime import date
from pathlib import Path
import build_food, build_seed
url = 'https://huggingface.co/datasets/openfoodfacts/product-database/resolve/main/<FILE>'
food = build_food.extract(url, Path('out'))
print('food.db:', food.stat().st_size // 1024 // 1024, 'МБ')
print('проблемы:', build_food.verify_food(food))
build_seed.build_seed(Path('out/seed.db'), Path('data'))
print('в seed перенесено:', build_food.fill_product_seed(Path('out/seed.db'), food, limit=30000))
print('seed.db:', Path('out/seed.db').stat().st_size // 1024, 'КБ')
manifest = build_food.package_food(food, version=date.today().strftime('%Y%m%d'))
print('манифест:', manifest.read_text(encoding='utf-8'))
"
```

Expected: `проблемы: []`, `seed.db` в пределах 2–5 МБ, в манифесте непустые `sha256`, `row_count` и `off_dump_date`. Если `seed.db` заметно больше — уменьшить `limit`; если сильно меньше — увеличить.

Это долгая сетевая операция. `out/` в `.gitignore`, артефакты не коммитятся.

- [ ] **Step 8: Commit**

```bash
cd /d/AndroidProject/KcalAI
git add tools/builddb
git commit -m "feat(builddb): извлечение среза Open Food Facts и наполнение product_seed"
```

---

### Task 6: `:core:fooddb` — чтение справочников

**Files:**
- Create: `core/fooddb/build.gradle.kts`
- Create: `core/fooddb/src/main/java/n7/kcalai/fooddb/FoodDb.kt`
- Create: `core/fooddb/src/main/java/n7/kcalai/fooddb/FoodDbQueries.kt`
- Create: `core/fooddb/src/main/java/n7/kcalai/fooddb/ProductRow.kt`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Test: `core/fooddb/src/test/java/n7/kcalai/fooddb/FoodDbQueriesTest.kt`

**Interfaces:**
- Consumes: `Nutriments` из `:core:model`; файлы `seed.db` / `food.db` из Task 4–5
- Produces:
  - `class FoodDb(connection: SQLiteConnection, hasFullDb: Boolean)` с `close()`
  - `object FoodDbFactory { fun open(driver: SQLiteDriver, seedPath: String, foodPath: String?): FoodDb }`
  - `data class ProductRow(barcode: String, name: String, brand: String?, nutriments: Nutriments, servingG: Int?)`
  - `data class GenericRow(id: Long, nameKey: String, nutriments: Nutriments, defaultPortionG: Int)`
  - `FoodDb.findByBarcode(gtin: String): ProductRow?`
  - `FoodDb.searchGeneric(query: String, limit: Int): List<GenericRow>`
  - `FoodDb.searchProducts(query: String, limit: Int): List<ProductRow>`
  - `FoodDb.genericById(id: Long): GenericRow?`
  - `FoodDb.portionGrams(genericId: Long, unit: String): Int?`

Ключевое решение: имя брендовой таблицы (`product` или `product_seed`) выбирается **один раз** при открытии соединения и подставляется в SQL. Это единственная ветка «есть полная база / нет».

- [ ] **Step 1: Добавить зависимости в каталог версий**

Дописать в `gradle/libs.versions.toml`:

```toml
[versions]
androidxSqlite = "2.7.1"
robolectric = "4.14.1"

[libraries]
androidx-sqlite = { group = "androidx.sqlite", name = "sqlite", version.ref = "androidxSqlite" }
androidx-sqlite-bundled = { group = "androidx.sqlite", name = "sqlite-bundled", version.ref = "androidxSqlite" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
```

(существующие секции не трогать, только дополнить)

- [ ] **Step 2: Создать модуль**

`core/fooddb/build.gradle.kts`:

```kotlin
plugins {
    id("n7.kcalai.android-library")
}

android {
    namespace = "n7.kcalai.fooddb"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.bundled)

    testImplementation(libs.androidx.sqlite.bundled)
}
```

Добавить в `settings.gradle.kts`: `include(":core:fooddb")`

- [ ] **Step 3: Написать падающий тест**

`core/fooddb/src/test/java/n7/kcalai/fooddb/FoodDbQueriesTest.kt`:

```kotlin
package n7.kcalai.fooddb

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.nio.file.Files
import n7.kcalai.model.Nutriments
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodDbQueriesTest {

    private val driver = BundledSQLiteDriver()
    private val tempDir: File = Files.createTempDirectory("fooddb").toFile()
    private var db: FoodDb? = null

    @After
    fun tearDown() {
        db?.close()
        tempDir.deleteRecursively()
    }

    // --- фикстуры ---

    private fun buildSeed(withSeedProducts: Boolean): String {
        val path = File(tempDir, "seed.db").absolutePath
        driver.open(path).use { conn ->
            conn.execSQL(
                "CREATE TABLE generic (id INTEGER PRIMARY KEY, name_key TEXT NOT NULL UNIQUE, " +
                    "kcal100 INTEGER NOT NULL, prot100 INTEGER NOT NULL, " +
                    "fat100 INTEGER NOT NULL, carb100 INTEGER NOT NULL, " +
                    "default_portion_g INTEGER NOT NULL)"
            )
            conn.execSQL(
                "CREATE TABLE generic_alias (generic_id INTEGER NOT NULL, alias TEXT NOT NULL, " +
                    "lang TEXT NOT NULL, PRIMARY KEY (generic_id, alias, lang))"
            )
            conn.execSQL(
                "CREATE VIRTUAL TABLE generic_fts USING fts5(alias, generic_id UNINDEXED, " +
                    "tokenize = 'unicode61 remove_diacritics 2')"
            )
            conn.execSQL(
                "CREATE TABLE portion_unit (generic_id INTEGER NOT NULL, unit TEXT NOT NULL, " +
                    "grams INTEGER NOT NULL, PRIMARY KEY (generic_id, unit))"
            )
            conn.execSQL(
                "CREATE TABLE product_seed (barcode TEXT PRIMARY KEY, name TEXT NOT NULL, " +
                    "brand TEXT, kcal100 INTEGER NOT NULL, prot100 INTEGER NOT NULL, " +
                    "fat100 INTEGER NOT NULL, carb100 INTEGER NOT NULL, serving_g INTEGER, " +
                    "popularity INTEGER NOT NULL DEFAULT 0)"
            )
            conn.execSQL(
                "CREATE VIRTUAL TABLE product_seed_fts USING fts5(name, brand, " +
                    "barcode UNINDEXED, tokenize = 'unicode61 remove_diacritics 2')"
            )

            conn.execSQL("INSERT INTO generic VALUES (1, 'buckwheat_boiled', 110, 410, 110, 2130, 150)")
            conn.execSQL("INSERT INTO generic VALUES (3, 'egg_chicken_raw', 157, 1270, 1150, 70, 60)")
            conn.execSQL("INSERT INTO generic_alias VALUES (1, 'гречка', 'ru')")
            conn.execSQL("INSERT INTO generic_alias VALUES (3, 'яйцо', 'ru')")
            conn.execSQL("INSERT INTO generic_fts (alias, generic_id) VALUES ('гречка', 1)")
            conn.execSQL("INSERT INTO generic_fts (alias, generic_id) VALUES ('яйцо', 3)")
            conn.execSQL("INSERT INTO portion_unit VALUES (3, 'шт', 60)")

            if (withSeedProducts) {
                conn.execSQL(
                    "INSERT INTO product_seed VALUES ('111', 'Творожок Сеня', 'Сеня', " +
                        "120, 900, 400, 1200, 100, 50)"
                )
                conn.execSQL(
                    "INSERT INTO product_seed_fts (name, brand, barcode) " +
                        "VALUES ('Творожок Сеня', 'Сеня', '111')"
                )
            }
        }
        return path
    }

    private fun buildFood(): String {
        val path = File(tempDir, "food.db").absolutePath
        driver.open(path).use { conn ->
            conn.execSQL(
                "CREATE TABLE product (barcode TEXT PRIMARY KEY, name TEXT NOT NULL, brand TEXT, " +
                    "kcal100 INTEGER NOT NULL, prot100 INTEGER NOT NULL, fat100 INTEGER NOT NULL, " +
                    "carb100 INTEGER NOT NULL, serving_g INTEGER, popularity INTEGER NOT NULL DEFAULT 0)"
            )
            conn.execSQL(
                "CREATE VIRTUAL TABLE product_fts USING fts5(name, brand, barcode UNINDEXED, " +
                    "tokenize = 'unicode61 remove_diacritics 2')"
            )
            conn.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")

            conn.execSQL(
                "INSERT INTO product VALUES ('111', 'Творожок Сеня', 'Сеня', 120, 900, 400, 1200, 100, 50)"
            )
            conn.execSQL(
                "INSERT INTO product VALUES ('222', 'Кефир Простоквашино', 'Простоквашино', " +
                    "53, 300, 250, 400, 200, 900)"
            )
            // два товара с общим словом «Молоко» — для проверки порядка по популярности
            conn.execSQL(
                "INSERT INTO product VALUES ('333', 'Молоко Сеня', 'Сеня', 60, 300, 320, 470, NULL, 50)"
            )
            conn.execSQL(
                "INSERT INTO product VALUES ('444', 'Молоко Простоквашино', 'Простоквашино', " +
                    "60, 300, 320, 470, NULL, 900)"
            )
            conn.execSQL(
                "INSERT INTO product_fts (name, brand, barcode) VALUES ('Творожок Сеня', 'Сеня', '111')"
            )
            conn.execSQL(
                "INSERT INTO product_fts (name, brand, barcode) " +
                    "VALUES ('Кефир Простоквашино', 'Простоквашино', '222')"
            )
            conn.execSQL(
                "INSERT INTO product_fts (name, brand, barcode) VALUES ('Молоко Сеня', 'Сеня', '333')"
            )
            conn.execSQL(
                "INSERT INTO product_fts (name, brand, barcode) " +
                    "VALUES ('Молоко Простоквашино', 'Простоквашино', '444')"
            )
        }
        return path
    }

    private fun openSeedOnly(): FoodDb =
        FoodDbFactory.open(driver, buildSeed(withSeedProducts = true), null).also { db = it }

    private fun openWithFullDb(): FoodDb =
        FoodDbFactory.open(driver, buildSeed(withSeedProducts = true), buildFood()).also { db = it }

    // --- generic работает всегда, независимо от докачки ---

    @Test
    fun `поиск по генерику работает без полной базы`() {
        val results = openSeedOnly().searchGeneric("гречка", limit = 10)

        assertEquals(1, results.size)
        assertEquals("buckwheat_boiled", results[0].nameKey)
        assertEquals(Nutriments(110, 410, 110, 2130), results[0].nutriments)
    }

    @Test
    fun `поиск по генерику работает с полной базой`() {
        val results = openWithFullDb().searchGeneric("гречка", limit = 10)

        assertEquals(1, results.size)
        assertEquals("buckwheat_boiled", results[0].nameKey)
    }

    @Test
    fun `порция в штуках берётся из portion_unit`() {
        assertEquals(60, openSeedOnly().portionGrams(genericId = 3, unit = "шт"))
    }

    @Test
    fun `неизвестная единица даёт null`() {
        assertNull(openSeedOnly().portionGrams(genericId = 3, unit = "ведро"))
    }

    @Test
    fun `генерик находится по идентификатору`() {
        val row = openSeedOnly().genericById(1)

        assertEquals("buckwheat_boiled", row?.nameKey)
        assertEquals(Nutriments(110, 410, 110, 2130), row?.nutriments)
        assertEquals(150, row?.defaultPortionG)
    }

    @Test
    fun `несуществующий генерик даёт null`() {
        assertNull(openSeedOnly().genericById(404))
    }

    @Test
    fun `пустой seed без товаров не находит штрих-код`() {
        val opened = FoodDbFactory.open(driver, buildSeed(withSeedProducts = false), null)
            .also { db = it }

        assertNull(opened.findByBarcode("111"))
    }

    // --- штрих-код: оба состояния ---

    @Test
    fun `штрих-код из seed находится без полной базы`() {
        val row = openSeedOnly().findByBarcode("111")

        assertEquals("Творожок Сеня", row?.name)
        assertEquals(Nutriments(120, 900, 400, 1200), row?.nutriments)
        assertEquals(100, row?.servingG)
    }

    @Test
    fun `штрих-код вне seed не находится без полной базы`() {
        assertNull(openSeedOnly().findByBarcode("222"))
    }

    @Test
    fun `штрих-код вне seed находится после докачки`() {
        assertEquals("Кефир Простоквашино", openWithFullDb().findByBarcode("222")?.name)
    }

    @Test
    fun `несуществующий штрих-код даёт null в обоих состояниях`() {
        assertNull(openSeedOnly().findByBarcode("999"))
        db?.close()
        db = null
        assertNull(openWithFullDb().findByBarcode("999"))
    }

    // --- поиск по брендам: оба состояния ---

    @Test
    fun `поиск по бренду без полной базы видит только seed`() {
        val results = openSeedOnly().searchProducts("Кефир", limit = 10)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `поиск по бренду после докачки видит весь срез`() {
        val results = openWithFullDb().searchProducts("Кефир", limit = 10)

        assertEquals(1, results.size)
        assertEquals("222", results[0].barcode)
    }

    @Test
    fun `результаты поиска по брендам упорядочены по популярности`() {
        val results = openWithFullDb().searchProducts("Молоко", limit = 10)

        assertEquals(listOf("444", "333"), results.map { it.barcode })
    }

    @Test
    fun `многословный запрос ищется как конъюнкция`() {
        val results = openWithFullDb().searchProducts("Молоко Простоквашино", limit = 10)

        assertEquals(listOf("444"), results.map { it.barcode })
    }

    @Test
    fun `пустой запрос не роняет поиск`() {
        assertTrue(openWithFullDb().searchProducts("   ", limit = 10).isEmpty())
        assertTrue(openWithFullDb().searchGeneric("", limit = 10).isEmpty())
    }

    @Test
    fun `спецсимволы FTS не роняют поиск`() {
        assertTrue(openWithFullDb().searchProducts("\"unbalanced", limit = 10).isEmpty())
    }
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он падает**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :core:fooddb:testDebugUnitTest --console=plain
```
Expected: FAIL — `Unresolved reference: FoodDb`, `FoodDbFactory`.

- [ ] **Step 5: Реализовать**

`core/fooddb/src/main/java/n7/kcalai/fooddb/ProductRow.kt`:

```kotlin
package n7.kcalai.fooddb

import n7.kcalai.model.Nutriments

/** Товар со штрих-кодом из `product` или `product_seed`. */
data class ProductRow(
    val barcode: String,
    val name: String,
    val brand: String?,
    val nutriments: Nutriments,
    val servingG: Int?,
)

/** Позиция генерик-таблицы. */
data class GenericRow(
    val id: Long,
    val nameKey: String,
    val nutriments: Nutriments,
    /** Типичная разовая порция в граммах — подставляется, когда вес в тексте не указан. */
    val defaultPortionG: Int,
)
```

`core/fooddb/src/main/java/n7/kcalai/fooddb/FoodDbQueries.kt`:

```kotlin
package n7.kcalai.fooddb

/**
 * Имена таблиц зависят от того, докачан ли полный срез. Выбор делается один раз
 * при открытии соединения — это единственная ветка «есть полная база / нет».
 */
internal class FoodDbQueries(hasFullDb: Boolean) {

    private val productTable = if (hasFullDb) "food.product" else "main.product_seed"
    private val productFts = if (hasFullDb) "food.product_fts" else "main.product_seed_fts"

    val findByBarcode: String =
        "SELECT barcode, name, brand, kcal100, prot100, fat100, carb100, serving_g " +
            "FROM $productTable WHERE barcode = ?"

    val searchProducts: String =
        "SELECT p.barcode, p.name, p.brand, p.kcal100, p.prot100, p.fat100, p.carb100, p.serving_g " +
            "FROM $productFts f JOIN $productTable p ON p.barcode = f.barcode " +
            "WHERE $productFts MATCH ? " +
            "ORDER BY p.popularity DESC, p.name LIMIT ?"

    val searchGeneric: String =
        "SELECT g.id, g.name_key, g.kcal100, g.prot100, g.fat100, g.carb100, g.default_portion_g " +
            "FROM generic_fts f JOIN generic g ON g.id = f.generic_id " +
            "WHERE generic_fts MATCH ? " +
            "GROUP BY g.id ORDER BY g.id LIMIT ?"

    val genericById: String =
        "SELECT id, name_key, kcal100, prot100, fat100, carb100, default_portion_g " +
            "FROM generic WHERE id = ?"

    val portionGrams: String =
        "SELECT grams FROM portion_unit WHERE generic_id = ? AND unit = ?"
}

/**
 * Готовит пользовательский ввод для FTS5 MATCH.
 *
 * Обычный текст попадает в MATCH как есть и может уронить запрос на несбалансированной
 * кавычке или операторе. Поэтому каждое слово оборачивается в кавычки, а внутренние
 * кавычки удваиваются.
 */
internal fun sanitizeFtsQuery(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val tokens = trimmed.split(Regex("\\s+"))
        .map { it.replace("\"", "\"\"") }
        .filter { it.isNotBlank() }

    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "\"$it\"" }
}
```

Санитайзинг превращает многословный запрос в конъюнкцию: `Молоко Простоквашино`
становится `"Молоко" "Простоквашино"` и находит только товары, где есть оба слова.
Операторы FTS5 (`OR`, `NEAR`, `*`) пользователю недоступны намеренно — они превращаются
в обычные слова, а не в синтаксис.

`core/fooddb/src/main/java/n7/kcalai/fooddb/FoodDb.kt`:

```kotlin
package n7.kcalai.fooddb

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import n7.kcalai.model.Nutriments

/**
 * Доступ к справочным базам. `seed.db` открыт как `main`, докачанный `food.db`
 * подключается как схема `food`.
 */
class FoodDb internal constructor(
    private val connection: SQLiteConnection,
    private val queries: FoodDbQueries,
) : AutoCloseable {

    fun findByBarcode(gtin: String): ProductRow? =
        connection.prepare(queries.findByBarcode).use { stmt ->
            stmt.bindText(1, gtin)
            if (stmt.step()) stmt.readProduct() else null
        }

    fun searchProducts(query: String, limit: Int): List<ProductRow> {
        val match = sanitizeFtsQuery(query) ?: return emptyList()
        return connection.prepare(queries.searchProducts).use { stmt ->
            stmt.bindText(1, match)
            stmt.bindLong(2, limit.toLong())
            buildList {
                while (stmt.step()) add(stmt.readProduct())
            }
        }
    }

    fun searchGeneric(query: String, limit: Int): List<GenericRow> {
        val match = sanitizeFtsQuery(query) ?: return emptyList()
        return connection.prepare(queries.searchGeneric).use { stmt ->
            stmt.bindText(1, match)
            stmt.bindLong(2, limit.toLong())
            buildList {
                while (stmt.step()) {
                    add(
                        GenericRow(
                            id = stmt.getLong(0),
                            nameKey = stmt.getText(1),
                            nutriments = Nutriments(
                                kcal100 = stmt.getInt(2),
                                prot100 = stmt.getInt(3),
                                fat100 = stmt.getInt(4),
                                carb100 = stmt.getInt(5),
                            ),
                            defaultPortionG = stmt.getInt(6),
                        )
                    )
                }
            }
        }
    }

    fun genericById(id: Long): GenericRow? =
        connection.prepare(queries.genericById).use { stmt ->
            stmt.bindLong(1, id)
            if (stmt.step()) {
                GenericRow(
                    id = stmt.getLong(0),
                    nameKey = stmt.getText(1),
                    nutriments = Nutriments(
                        kcal100 = stmt.getInt(2),
                        prot100 = stmt.getInt(3),
                        fat100 = stmt.getInt(4),
                        carb100 = stmt.getInt(5),
                    ),
                    defaultPortionG = stmt.getInt(6),
                )
            } else {
                null
            }
        }

    fun portionGrams(genericId: Long, unit: String): Int? =
        connection.prepare(queries.portionGrams).use { stmt ->
            stmt.bindLong(1, genericId)
            stmt.bindText(2, unit)
            if (stmt.step()) stmt.getInt(0) else null
        }

    override fun close() {
        connection.close()
    }
}

private fun androidx.sqlite.SQLiteStatement.readProduct(): ProductRow =
    ProductRow(
        barcode = getText(0),
        name = getText(1),
        brand = if (isNull(2)) null else getText(2),
        nutriments = Nutriments(
            kcal100 = getInt(3),
            prot100 = getInt(4),
            fat100 = getInt(5),
            carb100 = getInt(6),
        ),
        servingG = if (isNull(7)) null else getInt(7),
    )

object FoodDbFactory {

    /**
     * @param seedPath путь к seed.db (обязателен, лежит в filesDir после копирования из assets)
     * @param foodPath путь к докачанному food.db, либо null, если полная база ещё не скачана
     */
    fun open(driver: SQLiteDriver, seedPath: String, foodPath: String?): FoodDb {
        val connection = driver.open(seedPath)
        if (foodPath != null) {
            connection.prepare("ATTACH DATABASE ? AS food").use { stmt ->
                stmt.bindText(1, foodPath)
                stmt.step()
            }
        }
        connection.execSQL("PRAGMA query_only = ON")
        return FoodDb(connection, FoodDbQueries(hasFullDb = foodPath != null))
    }
}
```

- [ ] **Step 6: Запустить тесты и убедиться, что они проходят**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :core:fooddb:testDebugUnitTest --console=plain
```
Expected: `BUILD SUCCESSFUL`, 18 пройденных тестов.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts core/fooddb
git commit -m "feat(fooddb): чтение seed.db и food.db через ATTACH с FTS5-поиском"
```

---

### Task 7: `:core:database` — Room для пользовательских данных

**Files:**
- Create: `core/database/build.gradle.kts`
- Create: `core/database/src/main/java/n7/kcalai/database/DiaryEntryEntity.kt`
- Create: `core/database/src/main/java/n7/kcalai/database/UserFoodEntity.kt`
- Create: `core/database/src/main/java/n7/kcalai/database/DailyGoalEntity.kt`
- Create: `core/database/src/main/java/n7/kcalai/database/DiaryDao.kt`
- Create: `core/database/src/main/java/n7/kcalai/database/UserFoodDao.kt`
- Create: `core/database/src/main/java/n7/kcalai/database/GoalDao.kt`
- Create: `core/database/src/main/java/n7/kcalai/database/KcalDatabase.kt`
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts` (корневой)
- Test: `core/database/src/test/java/n7/kcalai/database/DiaryDaoTest.kt`

**Interfaces:**
- Consumes: `MealType`, `EntrySource` из `:core:model`
- Produces:
  - `@Database abstract class KcalDatabase : RoomDatabase` с `diaryDao()`, `userFoodDao()`, `goalDao()`
  - `DiaryDao.insert(entry: DiaryEntryEntity): Long`
  - `DiaryDao.observeDay(dateEpochDay: Long): Flow<List<DiaryEntryEntity>>`
  - `DiaryDao.delete(id: Long)`
  - `UserFoodDao.insert(food: UserFoodEntity): Long`
  - `UserFoodDao.findByBarcode(barcode: String): UserFoodEntity?`
  - `UserFoodDao.findById(id: Long): UserFoodEntity?`
  - `UserFoodDao.search(query: String, limit: Int): List<UserFoodEntity>`
  - `GoalDao.upsert(goal: DailyGoalEntity)`
  - `GoalDao.goalFor(dateEpochDay: Long): DailyGoalEntity?`

- [ ] **Step 1: Подключить KSP и Room**

В корневом `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
}
```

Дописать в `gradle/libs.versions.toml`:

```toml
[versions]
ksp = "2.2.10-2.0.2"
room = "2.8.5"

[libraries]
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

Версия KSP обязана быть парной к встроенному Kotlin 2.2.10 — иначе сборка падает
с несовпадением версий компилятора.

- [ ] **Step 2: Создать модуль**

`core/database/build.gradle.kts`:

```kotlin
plugins {
    id("n7.kcalai.android-library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "n7.kcalai.database"
}

// ksp — расширение верхнего уровня, не часть блока android
ksp {
    arg("room.generateKotlin", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.sqlite.bundled)
}
```

Добавить в `settings.gradle.kts`: `include(":core:database")`

- [ ] **Step 3: Определить версию coroutines, совпадающую с Room**

Room тянет `kotlinx-coroutines-core` транзитивно; тестовый артефакт должен быть той же версии.

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew -q :core:database:dependencies --configuration debugCompileClasspath --console=plain | grep kotlinx-coroutines-core | head -3
```
Expected: строка вида `org.jetbrains.kotlinx:kotlinx-coroutines-core:X.Y.Z`

Записать найденную версию в `gradle/libs.versions.toml`:

```toml
[versions]
coroutines = "X.Y.Z"

[libraries]
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```

и добавить в `core/database/build.gradle.kts`:

```kotlin
    testImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 4: Написать падающий тест**

`core/database/src/test/java/n7/kcalai/database/DiaryDaoTest.kt`:

```kotlin
package n7.kcalai.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DiaryDaoTest {

    private lateinit var tempDir: File
    private lateinit var db: KcalDatabase

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("kcaldb").toFile()
        db = Room.databaseBuilder<KcalDatabase>(File(tempDir, "kcal.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private fun entry(
        date: Long = 20_000L,
        meal: MealType = MealType.BREAKFAST,
        name: String = "Гречка",
        grams: Int = 200,
    ) = DiaryEntryEntity(
        id = 0,
        dateEpochDay = date,
        meal = meal,
        displayName = name,
        grams = grams,
        kcal100 = 110,
        prot100 = 410,
        fat100 = 110,
        carb100 = 2130,
        source = EntrySource.TEXT,
        foodRef = "generic:1",
        createdAt = 1_700_000_000_000L,
    )

    @Test
    fun `запись сохраняется и читается за свой день`() = runTest {
        db.diaryDao().insert(entry())

        val day = db.diaryDao().observeDay(20_000L).first()

        assertEquals(1, day.size)
        assertEquals("Гречка", day[0].displayName)
        assertEquals(200, day[0].grams)
    }

    @Test
    fun `запись другого дня не попадает в выборку`() = runTest {
        db.diaryDao().insert(entry(date = 20_000L))
        db.diaryDao().insert(entry(date = 20_001L, name = "Яйцо"))

        val day = db.diaryDao().observeDay(20_000L).first()

        assertEquals(listOf("Гречка"), day.map { it.displayName })
    }

    @Test
    fun `снимок нутриентов хранится в самой записи`() = runTest {
        val id = db.diaryDao().insert(entry())

        val stored = db.diaryDao().observeDay(20_000L).first().single()

        assertEquals(id, stored.id)
        assertEquals(110, stored.kcal100)
        assertEquals(2130, stored.carb100)
    }

    @Test
    fun `удаление убирает запись`() = runTest {
        val id = db.diaryDao().insert(entry())

        db.diaryDao().delete(id)

        assertEquals(emptyList<DiaryEntryEntity>(), db.diaryDao().observeDay(20_000L).first())
    }

    @Test
    fun `записи дня отсортированы по времени создания`() = runTest {
        db.diaryDao().insert(entry(name = "Второе").copy(createdAt = 2_000L))
        db.diaryDao().insert(entry(name = "Первое").copy(createdAt = 1_000L))

        val day = db.diaryDao().observeDay(20_000L).first()

        assertEquals(listOf("Первое", "Второе"), day.map { it.displayName })
    }

    @Test
    fun `свой продукт находится по штрих-коду`() = runTest {
        db.userFoodDao().insert(
            UserFoodEntity(
                id = 0,
                barcode = "4600000000001",
                name = "Творог с рынка",
                kcal100 = 121,
                prot100 = 1700,
                fat100 = 500,
                carb100 = 180,
                createdAt = 1_700_000_000_000L,
            )
        )

        val found = db.userFoodDao().findByBarcode("4600000000001")

        assertEquals("Творог с рынка", found?.name)
    }

    @Test
    fun `неизвестный штрих-код даёт null`() = runTest {
        assertNull(db.userFoodDao().findByBarcode("0000000000000"))
    }

    @Test
    fun `цель на дату это последняя цель не позже этой даты`() = runTest {
        db.goalDao().upsert(DailyGoalEntity(fromDateEpochDay = 19_000L, kcal = 2000, prot = 12000, fat = 6000, carb = 22000))
        db.goalDao().upsert(DailyGoalEntity(fromDateEpochDay = 20_000L, kcal = 1800, prot = 13000, fat = 5000, carb = 19000))

        assertEquals(2000, db.goalDao().goalFor(19_500L)?.kcal)
        assertEquals(1800, db.goalDao().goalFor(20_000L)?.kcal)
        assertEquals(1800, db.goalDao().goalFor(21_000L)?.kcal)
    }

    @Test
    fun `до первой цели возвращается null`() = runTest {
        db.goalDao().upsert(DailyGoalEntity(fromDateEpochDay = 20_000L, kcal = 1800, prot = 13000, fat = 5000, carb = 19000))

        assertNull(db.goalDao().goalFor(19_999L))
    }
}
```

- [ ] **Step 5: Запустить тест и убедиться, что он падает**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :core:database:testDebugUnitTest --console=plain
```
Expected: FAIL — `Unresolved reference: KcalDatabase`.

- [ ] **Step 6: Реализовать**

`core/database/src/main/java/n7/kcalai/database/DiaryEntryEntity.kt`:

```kotlin
package n7.kcalai.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType

/**
 * Запись дневника.
 *
 * КБЖУ хранится снимком, а не ссылкой: обновление справочника не должно
 * задним числом переписывать историю.
 */
@Entity(tableName = "diary_entry", indices = [Index("dateEpochDay")])
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val dateEpochDay: Long,
    val meal: MealType,
    val displayName: String,
    val grams: Int,
    val kcal100: Int,
    val prot100: Int,
    val fat100: Int,
    val carb100: Int,
    val source: EntrySource,
    val foodRef: String?,
    val createdAt: Long,
)
```

`core/database/src/main/java/n7/kcalai/database/UserFoodEntity.kt`:

```kotlin
package n7.kcalai.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Продукт, заведённый пользователем после промаха сканера. */
@Entity(
    tableName = "user_food",
    indices = [Index(value = ["barcode"], unique = true)],
)
data class UserFoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val barcode: String?,
    val name: String,
    val kcal100: Int,
    val prot100: Int,
    val fat100: Int,
    val carb100: Int,
    val createdAt: Long,
)
```

`core/database/src/main/java/n7/kcalai/database/DailyGoalEntity.kt`:

```kotlin
package n7.kcalai.database

import androidx.room.Entity
import androidx.room.PrimaryKey

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
```

`core/database/src/main/java/n7/kcalai/database/DiaryDao.kt`:

```kotlin
package n7.kcalai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {

    @Insert
    suspend fun insert(entry: DiaryEntryEntity): Long

    @Query("SELECT * FROM diary_entry WHERE dateEpochDay = :dateEpochDay ORDER BY createdAt, id")
    fun observeDay(dateEpochDay: Long): Flow<List<DiaryEntryEntity>>

    @Query("DELETE FROM diary_entry WHERE id = :id")
    suspend fun delete(id: Long)
}
```

`core/database/src/main/java/n7/kcalai/database/UserFoodDao.kt`:

```kotlin
package n7.kcalai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserFoodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(food: UserFoodEntity): Long

    @Query("SELECT * FROM user_food WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): UserFoodEntity?

    @Query("SELECT * FROM user_food WHERE id = :id")
    suspend fun findById(id: Long): UserFoodEntity?

    @Query("SELECT * FROM user_food WHERE name LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<UserFoodEntity>
}
```

`core/database/src/main/java/n7/kcalai/database/GoalDao.kt`:

```kotlin
package n7.kcalai.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface GoalDao {

    @Upsert
    suspend fun upsert(goal: DailyGoalEntity)

    @Query(
        "SELECT * FROM daily_goal WHERE fromDateEpochDay <= :dateEpochDay " +
            "ORDER BY fromDateEpochDay DESC LIMIT 1"
    )
    suspend fun goalFor(dateEpochDay: Long): DailyGoalEntity?
}
```

`core/database/src/main/java/n7/kcalai/database/KcalDatabase.kt`:

```kotlin
package n7.kcalai.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DiaryEntryEntity::class,
        UserFoodEntity::class,
        DailyGoalEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KcalDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun userFoodDao(): UserFoodDao
    abstract fun goalDao(): GoalDao
}
```

Room умеет хранить enum'ы без конвертеров, поэтому `MealType` и `EntrySource`
дополнительного кода не требуют.

- [ ] **Step 7: Запустить тесты и убедиться, что они проходят**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :core:database:testDebugUnitTest --console=plain
```
Expected: `BUILD SUCCESSFUL`, 9 пройденных тестов.

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts settings.gradle.kts core/database
git commit -m "feat(database): Room-схема дневника, своих продуктов и версионируемых целей"
```

---

### Task 8: `:core:repositories` — единая точка подстановки КБЖУ

**Files:**
- Create: `core/repositories/build.gradle.kts`
- Create: `core/repositories/src/main/java/n7/kcalai/repositories/FoodRepository.kt`
- Create: `core/repositories/src/main/java/n7/kcalai/repositories/DiaryRepository.kt`
- Create: `core/repositories/src/main/java/n7/kcalai/repositories/FoodCandidate.kt`
- Modify: `settings.gradle.kts`
- Test: `core/repositories/src/test/java/n7/kcalai/repositories/FoodRepositoryTest.kt`
- Test: `core/repositories/src/test/java/n7/kcalai/repositories/DiaryRepositoryTest.kt`

**Interfaces:**
- Consumes: `FoodDb`, `ProductRow`, `GenericRow` из `:core:fooddb`; `KcalDatabase` и DAO из `:core:database`; `Nutriments`, `NutrimentTotals`, `forGrams`, `plus`, `FoodRef`, `MealType`, `EntrySource` из `:core:model`
- Produces:
  - `data class FoodCandidate(ref: FoodRef, displayName: String, nutriments: Nutriments, servingG: Int?)`
  - `class FoodRepository(foodDb: FoodDb, userFoodDao: UserFoodDao)` с `suspend fun byBarcode(gtin: String): FoodCandidate?`, `suspend fun search(query: String, limit: Int): List<FoodCandidate>`, `suspend fun nutrimentsFor(ref: FoodRef): Nutriments?`
  - `class DiaryRepository(diaryDao: DiaryDao, goalDao: GoalDao)` с `suspend fun add(candidate: FoodCandidate, grams: Int, meal: MealType, date: Long, source: EntrySource, now: Long): Long`, `fun observeDay(date: Long): Flow<DayTotals>`
  - `data class DayTotals(entries: List<DiaryEntryEntity>, totals: NutrimentTotals)`

Порядок поиска в `FoodRepository.search`: свои продукты → генерик → брендовые товары. Свои — первыми, потому что их пользователь завёл руками и почти наверняка ищет именно их.

- [ ] **Step 1: Создать модуль**

`core/repositories/build.gradle.kts`:

```kotlin
plugins {
    id("n7.kcalai.android-library")
}

android {
    namespace = "n7.kcalai.repositories"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:fooddb"))
    api(project(":core:database"))

    testImplementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

Добавить в `settings.gradle.kts`: `include(":core:repositories")`

- [ ] **Step 2: Написать падающий тест для FoodRepository**

`core/repositories/src/test/java/n7/kcalai/repositories/FoodRepositoryTest.kt`:

```kotlin
package n7.kcalai.repositories

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import n7.kcalai.database.KcalDatabase
import n7.kcalai.database.UserFoodEntity
import n7.kcalai.fooddb.FoodDb
import n7.kcalai.fooddb.FoodDbFactory
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Nutriments
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FoodRepositoryTest {

    private val driver = BundledSQLiteDriver()
    private lateinit var tempDir: File
    private lateinit var foodDb: FoodDb
    private lateinit var room: KcalDatabase
    private lateinit var repo: FoodRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("repo").toFile()

        val seedPath = File(tempDir, "seed.db").absolutePath
        driver.open(seedPath).use { conn ->
            conn.execSQL(
                "CREATE TABLE generic (id INTEGER PRIMARY KEY, name_key TEXT NOT NULL UNIQUE, " +
                    "kcal100 INTEGER NOT NULL, prot100 INTEGER NOT NULL, fat100 INTEGER NOT NULL, " +
                    "carb100 INTEGER NOT NULL, default_portion_g INTEGER NOT NULL)"
            )
            conn.execSQL(
                "CREATE VIRTUAL TABLE generic_fts USING fts5(alias, generic_id UNINDEXED, " +
                    "tokenize = 'unicode61 remove_diacritics 2')"
            )
            conn.execSQL(
                "CREATE TABLE portion_unit (generic_id INTEGER NOT NULL, unit TEXT NOT NULL, " +
                    "grams INTEGER NOT NULL, PRIMARY KEY (generic_id, unit))"
            )
            conn.execSQL(
                "CREATE TABLE product_seed (barcode TEXT PRIMARY KEY, name TEXT NOT NULL, brand TEXT, " +
                    "kcal100 INTEGER NOT NULL, prot100 INTEGER NOT NULL, fat100 INTEGER NOT NULL, " +
                    "carb100 INTEGER NOT NULL, serving_g INTEGER, popularity INTEGER NOT NULL DEFAULT 0)"
            )
            conn.execSQL(
                "CREATE VIRTUAL TABLE product_seed_fts USING fts5(name, brand, barcode UNINDEXED, " +
                    "tokenize = 'unicode61 remove_diacritics 2')"
            )
            conn.execSQL("INSERT INTO generic VALUES (1, 'buckwheat_boiled', 110, 410, 110, 2130, 150)")
            conn.execSQL("INSERT INTO generic_fts (alias, generic_id) VALUES ('гречка', 1)")
            conn.execSQL(
                "INSERT INTO product_seed VALUES ('111', 'Гречка Мистраль', 'Мистраль', " +
                    "308, 1260, 330, 5710, 100, 90)"
            )
            conn.execSQL(
                "INSERT INTO product_seed_fts (name, brand, barcode) " +
                    "VALUES ('Гречка Мистраль', 'Мистраль', '111')"
            )
        }

        foodDb = FoodDbFactory.open(driver, seedPath, null)
        room = Room.databaseBuilder<KcalDatabase>(File(tempDir, "kcal.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        repo = FoodRepository(foodDb, room.userFoodDao())
    }

    @After
    fun tearDown() {
        foodDb.close()
        room.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `штрих-код из справочника даёт кандидата`() = runTest {
        val candidate = repo.byBarcode("111")

        assertEquals(FoodRef.Barcode("111"), candidate?.ref)
        assertEquals("Гречка Мистраль", candidate?.displayName)
        assertEquals(Nutriments(308, 1260, 330, 5710), candidate?.nutriments)
    }

    @Test
    fun `свой продукт перекрывает справочник по тому же штрих-коду`() = runTest {
        val id = room.userFoodDao().insert(
            UserFoodEntity(0, "111", "Моя гречка", 300, 1200, 300, 5600, 1L)
        )

        val candidate = repo.byBarcode("111")

        assertEquals(FoodRef.User(id), candidate?.ref)
        assertEquals("Моя гречка", candidate?.displayName)
    }

    @Test
    fun `неизвестный штрих-код даёт null`() = runTest {
        assertNull(repo.byBarcode("999"))
    }

    @Test
    fun `поиск возвращает и генерик и брендовый товар`() = runTest {
        val results = repo.search("гречка", limit = 10)

        assertTrue(results.any { it.ref == FoodRef.Generic(1) })
        assertTrue(results.any { it.ref == FoodRef.Barcode("111") })
    }

    @Test
    fun `генерик идёт выше брендового товара`() = runTest {
        val results = repo.search("гречка", limit = 10)

        assertEquals(FoodRef.Generic(1), results.first().ref)
    }

    @Test
    fun `свои продукты идут самыми первыми`() = runTest {
        val id = room.userFoodDao().insert(
            UserFoodEntity(0, null, "гречка с рынка", 300, 1200, 300, 5600, 1L)
        )

        val results = repo.search("гречка", limit = 10)

        assertEquals(FoodRef.User(id), results.first().ref)
    }

    @Test
    fun `кандидат-генерик несёт типичную порцию`() = runTest {
        val generic = repo.search("гречка", limit = 10).first { it.ref == FoodRef.Generic(1) }

        assertEquals(150, generic.servingG)
    }

    @Test
    fun `лимит соблюдается`() = runTest {
        assertEquals(1, repo.search("гречка", limit = 1).size)
    }

    @Test
    fun `nutrimentsFor находит значения по ссылке`() = runTest {
        assertEquals(Nutriments(110, 410, 110, 2130), repo.nutrimentsFor(FoodRef.Generic(1)))
        assertEquals(Nutriments(308, 1260, 330, 5710), repo.nutrimentsFor(FoodRef.Barcode("111")))
    }

    @Test
    fun `nutrimentsFor на протухшей ссылке даёт null`() = runTest {
        assertNull(repo.nutrimentsFor(FoodRef.Generic(404)))
        assertNull(repo.nutrimentsFor(FoodRef.User(404)))
    }
}
```

- [ ] **Step 3: Написать падающий тест для DiaryRepository**

`core/repositories/src/test/java/n7/kcalai/repositories/DiaryRepositoryTest.kt`:

```kotlin
package n7.kcalai.repositories

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.KcalDatabase
import n7.kcalai.model.EntrySource
import n7.kcalai.model.FoodRef
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.Nutriments
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DiaryRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var room: KcalDatabase
    private lateinit var repo: DiaryRepository

    private val buckwheat = FoodCandidate(
        ref = FoodRef.Generic(1),
        displayName = "Гречка",
        nutriments = Nutriments(110, 410, 110, 2130),
        servingG = null,
    )

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("diary").toFile()
        room = Room.databaseBuilder<KcalDatabase>(File(tempDir, "kcal.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        repo = DiaryRepository(room.diaryDao(), room.goalDao())
    }

    @After
    fun tearDown() {
        room.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `добавленная запись хранит снимок нутриентов`() = runTest {
        repo.add(buckwheat, grams = 200, meal = MealType.BREAKFAST, date = 20_000L,
            source = EntrySource.TEXT, now = 1L)

        val entry = repo.observeDay(20_000L).first().entries.single()

        assertEquals("Гречка", entry.displayName)
        assertEquals(110, entry.kcal100)
        assertEquals("generic:1", entry.foodRef)
    }

    @Test
    fun `итоги дня суммируют записи`() = runTest {
        repo.add(buckwheat, grams = 200, meal = MealType.BREAKFAST, date = 20_000L,
            source = EntrySource.TEXT, now = 1L)
        repo.add(buckwheat, grams = 100, meal = MealType.LUNCH, date = 20_000L,
            source = EntrySource.TEXT, now = 2L)

        val day = repo.observeDay(20_000L).first()

        // 300 г гречки: 110 ккал/100 г -> 330
        assertEquals(330, day.totals.kcal)
        assertEquals(1230, day.totals.protCg)
    }

    @Test
    fun `пустой день даёт нулевые итоги`() = runTest {
        val day = repo.observeDay(20_000L).first()

        assertEquals(NutrimentTotals.ZERO, day.totals)
        assertEquals(emptyList<Any>(), day.entries)
    }

    @Test
    fun `записи соседнего дня не влияют на итоги`() = runTest {
        repo.add(buckwheat, grams = 200, meal = MealType.BREAKFAST, date = 20_001L,
            source = EntrySource.TEXT, now = 1L)

        assertEquals(NutrimentTotals.ZERO, repo.observeDay(20_000L).first().totals)
    }

    @Test
    fun `цель дня берётся из последней подходящей записи`() = runTest {
        room.goalDao().upsert(DailyGoalEntity(19_000L, 2000, 12000, 6000, 22000))
        room.goalDao().upsert(DailyGoalEntity(20_000L, 1800, 13000, 5000, 19000))

        assertEquals(1800, repo.goalFor(20_500L)?.kcal)
    }

    @Test
    fun `до первой цели null`() = runTest {
        assertNull(repo.goalFor(20_500L))
    }
}
```

- [ ] **Step 4: Запустить тесты и убедиться, что они падают**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :core:repositories:testDebugUnitTest --console=plain
```
Expected: FAIL — `Unresolved reference: FoodRepository`, `DiaryRepository`, `FoodCandidate`.

- [ ] **Step 5: Реализовать**

`core/repositories/src/main/java/n7/kcalai/repositories/FoodCandidate.kt`:

```kotlin
package n7.kcalai.repositories

import n7.kcalai.model.FoodRef
import n7.kcalai.model.Nutriments

/** Продукт, готовый к добавлению в дневник: чем он является и какой у него КБЖУ. */
data class FoodCandidate(
    val ref: FoodRef,
    val displayName: String,
    val nutriments: Nutriments,
    val servingG: Int?,
)

/** Сериализация ссылки для хранения в дневнике. Формат стабилен — его читает «повторить». */
internal fun FoodRef.serialize(): String = when (this) {
    is FoodRef.Barcode -> "barcode:$gtin"
    is FoodRef.Generic -> "generic:$id"
    is FoodRef.User -> "user:$id"
}
```

`core/repositories/src/main/java/n7/kcalai/repositories/FoodRepository.kt`:

```kotlin
package n7.kcalai.repositories

import n7.kcalai.database.UserFoodDao
import n7.kcalai.database.UserFoodEntity
import n7.kcalai.fooddb.FoodDb
import n7.kcalai.fooddb.ProductRow
import n7.kcalai.model.FoodRef
import n7.kcalai.model.Nutriments

/**
 * Единственная точка, где продукт превращается в КБЖУ.
 *
 * Порядок источников: свои продукты -> генерик-таблица -> брендовые товары.
 * Свои идут первыми, потому что пользователь завёл их вручную и ищет именно их.
 */
class FoodRepository(
    private val foodDb: FoodDb,
    private val userFoodDao: UserFoodDao,
) {

    suspend fun byBarcode(gtin: String): FoodCandidate? {
        userFoodDao.findByBarcode(gtin)?.let { return it.toCandidate() }
        return foodDb.findByBarcode(gtin)?.toCandidate()
    }

    suspend fun search(query: String, limit: Int): List<FoodCandidate> {
        val own = userFoodDao.search(query, limit).map { it.toCandidate() }
        if (own.size >= limit) return own.take(limit)

        val generic = foodDb.searchGeneric(query, limit - own.size).map {
            FoodCandidate(
                ref = FoodRef.Generic(it.id),
                displayName = it.nameKey,
                nutriments = it.nutriments,
                // для генерика "порция" — это типичная разовая порция; она заполняет чипс,
                // когда в тексте нет веса ("немного гречки")
                servingG = it.defaultPortionG,
            )
        }
        val taken = own.size + generic.size
        if (taken >= limit) return (own + generic).take(limit)

        val products = foodDb.searchProducts(query, limit - taken).map { it.toCandidate() }
        return (own + generic + products).take(limit)
    }

    /** Актуальные значения по ссылке. `null`, если ссылка протухла после обновления справочника. */
    suspend fun nutrimentsFor(ref: FoodRef): Nutriments? = when (ref) {
        is FoodRef.Barcode -> foodDb.findByBarcode(ref.gtin)?.nutriments
        is FoodRef.User -> userFoodDao.findById(ref.id)?.toNutriments()
        is FoodRef.Generic -> foodDb.genericById(ref.id)?.nutriments
    }
}

private fun ProductRow.toCandidate(): FoodCandidate =
    FoodCandidate(
        ref = FoodRef.Barcode(barcode),
        displayName = name,
        nutriments = nutriments,
        servingG = servingG,
    )

private fun UserFoodEntity.toCandidate(): FoodCandidate =
    FoodCandidate(
        ref = FoodRef.User(id),
        displayName = name,
        nutriments = toNutriments(),
        servingG = null,
    )

private fun UserFoodEntity.toNutriments(): Nutriments =
    Nutriments(kcal100 = kcal100, prot100 = prot100, fat100 = fat100, carb100 = carb100)
```

`core/repositories/src/main/java/n7/kcalai/repositories/DiaryRepository.kt`:

```kotlin
package n7.kcalai.repositories

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import n7.kcalai.database.DailyGoalEntity
import n7.kcalai.database.DiaryDao
import n7.kcalai.database.DiaryEntryEntity
import n7.kcalai.database.GoalDao
import n7.kcalai.model.EntrySource
import n7.kcalai.model.MealType
import n7.kcalai.model.NutrimentTotals
import n7.kcalai.model.Nutriments
import n7.kcalai.model.forGrams
import n7.kcalai.model.plus

/** Записи дня вместе с посчитанными итогами. */
data class DayTotals(
    val entries: List<DiaryEntryEntity>,
    val totals: NutrimentTotals,
)

class DiaryRepository(
    private val diaryDao: DiaryDao,
    private val goalDao: GoalDao,
) {

    /** Кладёт в дневник снимок КБЖУ, а не ссылку: обновление справочника не трогает историю. */
    suspend fun add(
        candidate: FoodCandidate,
        grams: Int,
        meal: MealType,
        date: Long,
        source: EntrySource,
        now: Long,
    ): Long = diaryDao.insert(
        DiaryEntryEntity(
            id = 0,
            dateEpochDay = date,
            meal = meal,
            displayName = candidate.displayName,
            grams = grams,
            kcal100 = candidate.nutriments.kcal100,
            prot100 = candidate.nutriments.prot100,
            fat100 = candidate.nutriments.fat100,
            carb100 = candidate.nutriments.carb100,
            source = source,
            foodRef = candidate.ref.serialize(),
            createdAt = now,
        )
    )

    fun observeDay(date: Long): Flow<DayTotals> =
        diaryDao.observeDay(date).map { entries ->
            DayTotals(entries = entries, totals = entries.sumTotals())
        }

    suspend fun delete(id: Long) = diaryDao.delete(id)

    suspend fun goalFor(date: Long): DailyGoalEntity? = goalDao.goalFor(date)
}

private fun List<DiaryEntryEntity>.sumTotals(): NutrimentTotals =
    fold(NutrimentTotals.ZERO) { acc, entry ->
        acc + Nutriments(entry.kcal100, entry.prot100, entry.fat100, entry.carb100)
            .forGrams(entry.grams)
    }
```

- [ ] **Step 6: Запустить тесты и убедиться, что они проходят**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :core:repositories:testDebugUnitTest --console=plain
```
Expected: `BUILD SUCCESSFUL`, 16 пройденных тестов.

- [ ] **Step 7: Прогнать все тесты проекта**

Run:
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew testDebugUnitTest --console=plain
```
Expected: `BUILD SUCCESSFUL`. Суммарно 52 теста Kotlin (9 model + 18 fooddb + 9 database + 16 repositories).

И тесты сборщика базы:
```bash
cd tools/builddb && python -m unittest discover -v
```
Expected: `OK`, 25 тестов (13 build_seed + 12 build_food).

- [ ] **Step 8: Commit**

```bash
cd /d/AndroidProject/KcalAI
git add settings.gradle.kts core/repositories
git commit -m "feat(repositories): подстановка КБЖУ по ссылке и итоги дня со снимками"
```

---

## Что этот план даёт на выходе

Слой данных, полностью покрытый тестами и работающий офлайн:

- `seed.db` собирается из версионируемых CSV и проверяется на инварианты
- `food.db` извлекается из дампа Open Food Facts с отбраковкой мусора и дедупом
- поиск по названию и по штрих-коду работает в обоих состояниях — до и после докачки
- дневник пишется со снимком КБЖУ, итоги дня считаются, цели версионируются

UI нет — он в Плане 3.

## Следующие планы

| План | Состав | Результат |
|---|---|---|
| 2 — Резолверы | `:core:resolver:api`, `:core:resolver:impl` (штрих-код + парсер количеств и единиц + ранжирование, порог `confidence` 0.8) | `"200 г гречки, 2 яйца"` → `List<ResolvedItem>` |
| 3 — Работающее приложение | `:core:designsystem`, `:feature:entry`, `:feature:diary`, `:feature:onboarding`, `:app`, Dagger-мультибиндинг резолверов, копирование `seed.db` из assets | Устанавливаемое приложение: дневник ведётся текстом |
| 4 — Скан и полная база | `:feature:scanner` (CameraX + ML Kit bundled), докачка `food.db` через WorkManager с sha256, `:feature:settings`, экспорт | Скан штрих-кода, форма «новый продукт» при промахе, полный срез |
