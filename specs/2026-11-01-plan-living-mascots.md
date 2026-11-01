# Живые персонажи — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Яйцо, Масло и Зёрнышко замечают человека, отвечают на касания и еду, спят и скучают, помнят привычки и пишут первыми — в уведомлениях и в ленте, каждый раз новыми фразами.

**Architecture:** Новый KMP-модуль `:core:mascot` — «мозг»: факты дня из базы и моделей персонализации, планировщик «когда писать», движок фраз со сборкой из слотов и памятью «не повторяться», три голоса. `:core:ui` получает куклу (выражения, взгляд, конечные действия). `:feature:diary` — сообщения в ленте, вопросы, облачка, жесты. `:app` — WorkManager, уведомления-переписки, кнопка «Записать», разрешение, датчик тряски.

**Tech Stack:** Kotlin 2.2.10 (KMP), Compose Multiplatform 1.9.3, Room 2.8.5 KMP, kotlinx-datetime 0.7.1, WorkManager 2.11.2, androidx.core (NotificationCompat, ShortcutManagerCompat).

**Spec:** `specs/2026-11-01-living-mascots-design.md`

## Global Constraints

- Тесты в стиле TDD: сначала падает, после правки проходит (CLAUDE.md). Коммиты — сразу в `main`.
- Неиспользуемый код удаляется (CLAUDE.md).
- Сборка: `$env:JAVA_HOME = "C:\Users\eug79\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"` (jbr Android Studio сломан). Тесты KMP-модулей — `:<module>:testAndroidHostTest`, приложения — `:app:testDebugUnitTest`.
- Устройство — только `emulator-5554`; на телефоне настоящий дневник, connected-тесты удаляют приложение.
- Ни одной бесконечной анимации: каждое действие конечно, без событий персонажи замирают.
- Цифры в репликах — только из фактов; в тексте вариантов цифр нет вовсе.
- Запретные корни в репликах: `обжор`, `толст`, `жирдя`, `позор`, `стыд`, `срыв`, `сорвал`, `диет`, `похуд`, `вредн`, `нельзя`, `запрет`, `лишн`, `виноват`, `наказ`.
- Родовые формы человека (`ел`, `ела`, `забыл`, `пропал`, `голоден`, `сыт`, `рад`, `устал`, `вернулся`…) — только внутри `{м|ж}`.
- Бюджет уведомлений: ≤ 2 в день, ≥ 3 ч между, не раньше 90 мин после открытия приложения, окно бодрствования 8…22 по умолчанию; 3 проигнорированных подряд → ≤ 1 в день; 6 подряд → только «пропал» на 2/4/7/14-й день.
- Длина: уведомление ≤ 180 символов, облачко ≤ 70, ответ в ленте ≤ 140.
- Персонажи — на «ты»; интерфейс (шиты, подсказки композера) — на «вы».
- Фиксированные тексты:
  - вопрос имени (Яйцо): «Кстати, как тебя зовут? Буду знать, к кому обращаться.» — кнопки «Сказать», «Не скажу»;
  - шит имени: заголовок «Как тебя зовут?», поле «Имя», кнопка «Готово»;
  - вопрос о сообщениях (Зёрнышко): «Можно мы будем иногда писать тебе сами? Если пропадёшь или забудешь поесть. Не чаще пары раз в день — честно.» — кнопки «Пишите», «Не надо»;
  - канал: «Сообщения от персонажей», описание «Яйцо, Масло и Зёрнышко пишут первыми — не чаще пары раз в день»;
  - собеседник в уведомлении — «Ты»; кнопка — «Записать: {еда}».

## Review Focus

1. Свежая установка без истории: фактов почти нет — ничего не падает, рецепты без недостающих фактов всё равно складываются (тест в задаче 3 и 6).
2. Сообщение около полуночи: день сообщения — по местной зоне, в ленту оно встаёт в свой день (тест в задаче 8).
3. Длинные и странные названия еды (капс бренда, кавычки, 80 символов): нормализуются и режутся, уведомление влезает в лимит (тесты в задачах 2 и 4).
4. Писать нельзя (нет согласия, «Не надо», уведомления выключены в системе): мозг не пишет и не тратит бюджет (тест в задаче 8, проверка в задаче 10).
5. Тап-спам и перетаскивание во время смены места: счётчик не переполняется, персонаж возвращается на место (тест в задаче 12).

---

### Task 1: Личности — в `:core:model`

**Files:**
- Create: `core/model/src/commonMain/kotlin/n7/kcalai/model/Mascot.kt`, `core/model/src/commonMain/kotlin/n7/kcalai/model/Sex.kt`
- Create: `core/model/src/commonTest/kotlin/n7/kcalai/model/MascotTest.kt`
- Modify: `core/ui/src/commonMain/kotlin/n7/kcalai/ui/mascot/Mascot.kt` (enum уходит; `internal val Mascot.art`, `val Mascot.macro`, `Macro.mascot` остаётся)
- Modify: `feature/diary/.../Parts.kt` (`dominantMacro` = `owner().macro`), `GoalHelper.kt` (`val Sex.title`), импорты в `Bubbles.kt`, `Feed.kt`, `MascotPlacement.kt`, `MascotStage.kt`, тестах `MascotPlacementTest`, `SpeakerTest`, `GoalHelperTest`

**Interfaces — Produces:**
```kotlin
// n7.kcalai.model
enum class Mascot { EGG, BUTTER, GRAIN }
val Mascot.title: String              // «Яйцо», «Масло», «Зёрнышко»
fun Nutriments.owner(): Mascot         // правило «по граммам» из Parts.kt, переезжает сюда с комментарием
fun NutrimentTotals.owner(): Mascot
enum class Sex { MALE, FEMALE }
// n7.kcalai.ui.mascot
val Mascot.macro: Macro
```

- [ ] Step 1: тест `MascotTest`: `` `хозяин продукта — макрос, которого больше по граммам` `` (творог 17/5/3 → EGG; масло 1/82/1 → BUTTER; гречка 4/1/21 → GRAIN; ничья белка и жира → EGG), `` `имена троих` `` (title).
- [ ] Step 2: `.\gradlew.bat :core:model:testAndroidHostTest` — FAIL (нет `Mascot`).
- [ ] Step 3: перенос, правка импортов (grep `n7.kcalai.ui.mascot.Mascot`), `Sex.title` в GoalHelper.
- [ ] Step 4: `.\gradlew.bat :core:model:testAndroidHostTest :core:ui:testAndroidHostTest :feature:diary:testAndroidHostTest` — PASS.
- [ ] Step 5: commit `refactor(model): Яйцо, Масло, Зёрнышко и пол — в core:model`.

### Task 2: Модуль `:core:mascot` и русские мелочи

**Files:**
- Modify: `settings.gradle.kts` (`include(":core:mascot")`)
- Create: `core/mascot/build.gradle.kts` (плагин `n7.kcalai.kmp-library`, namespace `n7.kcalai.mascot`; commonMain: `api(:core:model)`, `api(:core:database)`, `api(:core:personal)`, `api(libs.kotlinx.datetime)`, `api(libs.kotlinx.coroutines.core)`; commonTest: `libs.kotlinx.coroutines.test`)
- Create: `core/mascot/src/commonMain/kotlin/n7/kcalai/mascot/voice/Russian.kt`
- Test: `core/mascot/src/commonTest/kotlin/n7/kcalai/mascot/voice/RussianTest.kt`

**Interfaces — Produces:**
```kotlin
fun hoursPhrase(minutes: Int): String   // вниз до получаса: 60→«час», 90→«полтора часа», 150→«два с половиной часа», 180→«три часа», 330→«пять с половиной часов», 720→«двенадцать часов»; <60 → «меньше часа»
fun daysPhrase(days: Int): String       // 1 «день», 2 «два дня», 5 «пять дней», 7 «неделю», 8..13 «больше недели», 14 «две недели», 15..29 «больше двух недель», ≥30 «больше месяца»
fun countPhrase(n: Int, one: String, few: String, many: String): String  // «6 дней»→ словами до 10: «шесть дней», дальше цифрой: «14 дней»
fun ordinal(n: Int): String             // 2 «второй» … 10 «десятый», дальше «11-й»
fun foodName(display: String): String   // «Гречка отварная»→«гречка отварная»; «КЕФИР 1%» и латиница — как есть; длиннее 32 — по границе слова
fun weekdayName(day: DayOfWeek): String // «понедельник»…
fun clock(hour: Int, minute: Int): String // «09:05»
```

- [ ] Step 1: `RussianTest` — по одному тесту на функцию с перечисленными значениями; плюс `` `длинное название режется по слову` `` («Творог мягкий обезжиренный «Простоквашино» в ванночке» → не длиннее 32, без обрывка слова и без висящих кавычек/запятых).
- [ ] Step 2: `.\gradlew.bat :core:mascot:testAndroidHostTest` — FAIL.
- [ ] Step 3: реализовать.
- [ ] Step 4: PASS.
- [ ] Step 5: commit `feat(mascot): модуль мозга персонажей и русские числительные`.

### Task 3: Движок фраз

**Files:**
- Create: `core/mascot/src/commonMain/kotlin/n7/kcalai/mascot/Facts.kt`, `.../voice/Topic.kt`, `.../voice/Line.kt`, `.../voice/Bank.kt`, `.../voice/Composer.kt`
- Test: `core/mascot/src/commonTest/kotlin/n7/kcalai/mascot/voice/ComposerTest.kt`

**Interfaces — Produces:**
```kotlin
// n7.kcalai.mascot
data class MissedMeal(val meal: MealType, val usualHour: Int, val usualFood: String?, val usualGrams: Int?)
enum class Chronotype { LARK, OWL }
enum class PartOfDay { MORNING, DAY, EVENING, NIGHT }   // 5–11, 12–16, 17–22, 23–4
enum class Bond { NEW, FAMILIAR, CLOSE }                // ≤3 дней, ≤30, больше
data class Facts(
    val now: LocalDateTime,
    val name: String? = null, val sex: Sex? = null, val daysTogether: Int = 0,
    val minutesSinceLastEntry: Int? = null, val lastFood: String? = null, val lastEntryAt: LocalTime? = null,
    val todayEntries: Int = 0, val missed: MissedMeal? = null,
    val protLeftG: Int? = null, val protIdea: String? = null,
    val streakDays: Int = 0, val daysAway: Int = 0, val favorite: String? = null,
    val yesterdayOnGoal: Boolean = false, val chronotype: Chronotype? = null,
    val food: String? = null, val foodDaysInRow: Int = 0,
) { val partOfDay: PartOfDay; val weekend: Boolean; val bond: Bond }
internal fun Facts.value(key: String): String?   // плейсхолдеры, см. таблицу ниже

// n7.kcalai.mascot.voice
enum class Topic(val kind: TopicKind) { /* см. Task 4 */ }
enum class TopicKind(val maxLength: Int) { NOTE(180), SPEECH(70), ANSWER(140) }
class Line(val text: String, val fits: (Facts) -> Boolean = { true }) {
    val id: String            // FNV-1a 32 бит от text, hex
    fun renders(facts: Facts): Boolean
    fun render(facts: Facts): String
}
class Slot(val lines: List<Line>, val skip: Double)
class Recipe(val slots: List<Slot>)
class Voice(val mascot: Mascot, val topics: Map<Topic, List<Recipe>>)
fun voice(mascot: Mascot, block: VoiceBuilder.() -> Unit): Voice
// VoiceBuilder.pool { +"…"; "…" given { it.weekend } } : List<Line>
// VoiceBuilder.topic(Topic) { recipe { slot(skip = 0.4) { … }; slot(sharedPool, skip = 0.3) }; say { … } }
data class Utterance(val text: String, val lineIds: List<String>)
fun Voice.compose(topic: Topic, facts: Facts, recent: Set<String>, random: Random): Utterance?
```

Плейсхолдеры: `name`, `gap` (`hoursPhrase`, только при ≥ 60 мин), `last`, `lastTime`, `meal` («обед»), `mealGen` («обеда»), `usualTime` («13:00»), `usual`, `usualGrams` («200 г»), `protLeft` («40 г»), `protIdea`, `streak` (`countPhrase` дней), `away` (`daysPhrase`), `fav`, `food`, `foodRun` (`ordinal` + « день подряд»), `today` (`weekdayName`). Род: `{м|ж}` — только при известном `sex`. Каждая строка после подстановки начинается с заглавной.

Сборка: рецепты перемешиваются; в слоте берутся строки, которые `renders`, из них свежие (id не в `recent`), если свежих нет — любые; пустой обязательный слот (`skip == 0.0`) бракует рецепт, пробуется следующий; пустой необязательный пропускается.

- [ ] Step 1: `ComposerTest` на маленьком тестовом голосе: `` `плейсхолдер подставляется из фактов` ``, `` `строка без нужного факта не выбирается` ``, `` `родовая строка — только когда пол известен` `` (MALE → «ел», FEMALE → «ела», null → строка не выбрана), `` `недавно сказанное не повторяется, пока есть свежее` ``, `` `всё сказано — повтор лучше молчания` ``, `` `рецепт с пустым обязательным слотом уступает следующему` ``, `` `тот же seed — та же фраза` ``, `` `каждая часть начинается с заглавной` ``, `` `свежая установка — без фактов фраза всё равно складывается` `` (рецепт из строк без плейсхолдеров).
- [ ] Step 2: FAIL.
- [ ] Step 3: реализовать.
- [ ] Step 4: PASS.
- [ ] Step 5: commit `feat(mascot): движок фраз — слоты, факты, род и память о сказанном`.

### Task 4: Три голоса

**Files:**
- Create: `core/mascot/src/commonMain/kotlin/n7/kcalai/mascot/voice/Egg.kt`, `Butter.kt`, `Grain.kt`, `Voices.kt`
- Modify: `voice/Topic.kt` (полный список)
- Test: `core/mascot/src/commonTest/kotlin/n7/kcalai/mascot/voice/VoicesTest.kt`

**Interfaces — Produces:**
```kotlin
object Voices {
    fun of(mascot: Mascot): Voice
    val cast: Map<Topic, Set<Mascot>>   // кто говорит на какую тему
}
```

Темы и состав (E — Яйцо, B — Масло, G — Зёрнышко):
- NOTE: `COMEBACK` EBG, `MISSED_MEAL` EBG, `LONG_GAP` BG, `EMPTY_DAY` EG, `STREAK_KEEP` E, `PROTEIN_LEFT` E, `YESTERDAY_WIN` B.
- ANSWER: `LOGGED` EBG (ответ на «Записать» из шторки), `NAME_THANKS` E, `WRITE_YES` G, `WRITE_NO` G, `WRITE_DENIED` G.
- SPEECH (все трое, если не сказано иное): `POKE`, `POKE_ANNOYED`, `TRICK`, `HELD`, `DROPPED`, `THROWN`, `PETTED`, `WOKEN_NIGHT`, `WOKEN_IDLE`, `GREET_HOURS`, `GREET_DAYS`, `OPENED`, `MEAL_FIRST`, `MEAL_NEW`, `MEAL_REPEAT`, `MEAL_LATE`, `MEAL_OWNER`, `SHAKEN`, `GOAL_REACHED` (EB).

NOTE-темы — 2–3 рецепта: [зачин персонажа, необяз.] + [суть темы] + [личное: имя, любимое, хронотип, выходные, дни вместе, необяз.] + [концовка персонажа, необяз.]. Зачины и концовки — общие пулы персонажа с условиями по времени суток и `bond`. Иногда персонаж упоминает двух других. SPEECH и ANSWER — один слот `say`, 6–10 вариантов.

- [ ] Step 1: `VoicesTest`: `` `каждый из состава говорит на свою тему` `` (по `cast`, богатые факты → не `null`), `` `ни одного незакрытого плейсхолдера` ``, `` `реплика укладывается в длину своей темы` `` (с фактами-рекордсменами: имя 20 символов, еда 32 символа), `` `в вариантах нет цифр` ``, `` `в вариантах нет запретных корней` ``, `` `родовые формы только в альтернативах` ``, `` `плейсхолдеры только известные` ``, `` `id вариантов не повторяются` ``, `` `двести сообщений подряд — почти все разные` `` (по каждой NOTE-теме и говорящему: окно памяти 40 последних id, ≥ 90% уникальных текстов, ни одного повтора внутри окна из 20).
- [ ] Step 2: FAIL.
- [ ] Step 3: написать голоса (характеры — в спеке).
- [ ] Step 4: PASS.
- [ ] Step 5: commit `feat(mascot): голоса Яйца, Масла и Зёрнышка`.

### Task 5: Хранение — сообщения и профиль

**Files:**
- Create: `core/database/src/commonMain/kotlin/n7/kcalai/database/MascotEntities.kt`, `MascotDao.kt`
- Modify: `KcalDatabase.kt` (две сущности, `mascotDao()`, `KCAL_DB_VERSION = 5`, `autoMigrations = [AutoMigration(from = 4, to = 5)]`)
- Create (сборкой): `core/database/schemas/n7.kcalai.database.KcalDatabase/5.json`
- Test: существующий `SchemaExportTest`

**Interfaces — Produces:**
```kotlin
enum class Delivery { FEED, NOTIFICATION }
enum class WriteConsent { UNASKED, ALLOWED, DECLINED }
@Entity(tableName = "mascot_message", indices = [Index("dateEpochDay"), Index("createdAt")])
data class MascotMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mascot: Mascot, val topic: String, val text: String,
    val lineIds: String,            // через запятую
    val delivery: Delivery, val dateEpochDay: Long, val createdAt: Long, val openedAt: Long? = null,
)
@Entity(tableName = "mascot_profile")
data class MascotProfileEntity(
    @PrimaryKey val id: Int = 0, val name: String? = null, val nameAsked: Boolean = false,
    val sex: Sex? = null, val consent: WriteConsent = WriteConsent.UNASKED, val lastOpenAt: Long? = null,
)
@Dao interface MascotDao {
    @Insert suspend fun insert(message: MascotMessageEntity): Long
    fun observeDay(dateEpochDay: Long): Flow<List<MascotMessageEntity>>        // ORDER BY createdAt, id
    suspend fun recent(limit: Int): List<MascotMessageEntity>                  // новые первыми
    suspend fun findById(id: Long): MascotMessageEntity?
    suspend fun markOpened(id: Long, at: Long)                                 // только где openedAt IS NULL
    suspend fun markAllOpened(at: Long)
    fun observeProfile(): Flow<MascotProfileEntity?>
    suspend fun profile(): MascotProfileEntity?
    @Upsert suspend fun upsertProfile(profile: MascotProfileEntity)
}
```

- [ ] Step 1: поднять `KCAL_DB_VERSION` до 5 и добавить сущности/DAO/автомиграцию; `.\gradlew.bat :core:database:testAndroidHostTest` — FAIL на `SchemaExportTest` (нет `5.json`), если схема ещё не выгружена.
- [ ] Step 2: `.\gradlew.bat :core:database:kspDebugKotlinAndroid` (или сборка модуля) — схема 5 появляется.
- [ ] Step 3: PASS; закоммитить схему.
- [ ] Step 4: commit `feat(database): сообщения персонажей и профиль, миграция 4 → 5`.

### Task 6: Факты дня

**Files:**
- Create: `core/personal/src/commonMain/kotlin/n7/kcalai/personal/MealClock.kt` (переезд `mealForHour`, `MEAL_WINDOWS`, `CLUSTER_GAP_MS`, `lastMainMealBefore` из `Feed.kt`; `MEAL_WINDOWS` становится публичным)
- Move tests: пять тестов «приём по часу» из `FeedTest` → `core/personal/src/commonTest/.../MealClockTest.kt`
- Create: `core/mascot/src/commonMain/kotlin/n7/kcalai/mascot/FactsBuilder.kt`
- Test: `core/mascot/src/commonTest/kotlin/n7/kcalai/mascot/FactsBuilderTest.kt`, фейки `core/mascot/src/commonTest/kotlin/n7/kcalai/mascot/Fakes.kt`

**Interfaces — Produces:**
```kotlin
data class Rhythm(val wakeHour: Int, val sleepHour: Int)   // по раскладке дня: первый приём −1 (6…10), последний +3 (21…23); без неё 8 и 22
data class Suggestion(val candidate: FoodCandidate, val meal: MealType)
data class Snapshot(val facts: Facts, val rhythm: Rhythm, val suggestions: Map<Topic, Suggestion>)
class FactsBuilder(diaryDao: DiaryDao, goalDao: GoalDao, personal: PersonalRepository, zone: TimeZone) {
    suspend fun build(now: Instant, profile: MascotProfileEntity?): Snapshot
}
```
Правила: история — 90 дней; `streakDays` — подряд дни с записями, кончая вчерашним; `daysAway` — сегодня минус день последней записи; `yesterdayOnGoal` — вчерашние ккал в 90…110% вчерашней цели; `favorite` — самый частый продукт истории, если встречался ≥ 3 раз; `daysTogether` — от первой записи в окне; `missed` — из `PersonalRepository.mealGap`, с подсказкой `MISSED_MEAL`; `protLeftG` — цель белка минус съеденное сегодня, только при цели белка > 0 и записях сегодня; `protIdea` — самый белковый продукт лучшего варианта `remainingPlan`, с подсказкой `PROTEIN_LEFT`; подсказка `LONG_GAP` — первое из `predictions`; `chronotype` — первый приём ≤ 7 → LARK, последний ≥ 21 → OWL.

- [ ] Step 1: `MealClockTest` (перенос) — `:core:personal:testAndroidHostTest` FAIL (нет `mealForHour` в personal).
- [ ] Step 2: перенос, импорт в `Feed.kt`/`DiaryViewModel.kt`; PASS (`:core:personal`, `:feature:diary`).
- [ ] Step 3: `FactsBuilderTest`: `` `серия — подряд дни до вчера` ``, `` `сколько дней не было` ``, `` `минуты с последней записи` ``, `` `вчера в цель — в пределах десяти процентов` ``, `` `любимое — только если ело хотя бы трижды` `` (формулировка без рода), `` `остаток белка только при цели белка` ``, `` `пропущенный обед с обычной едой и подсказкой` ``, `` `ритм по привычкам и по умолчанию` ``, `` `пустая история — факты пустые, без падений` ``. FAIL.
- [ ] Step 4: реализовать; PASS.
- [ ] Step 5: commit `feat(mascot): факты дня для персонажей; правило приёма по часу — в core:personal`.

### Task 7: Когда писать

**Files:**
- Create: `core/mascot/src/commonMain/kotlin/n7/kcalai/mascot/NudgePlanner.kt`
- Test: `core/mascot/src/commonTest/kotlin/n7/kcalai/mascot/NudgePlannerTest.kt`

**Interfaces — Produces:**
```kotlin
data class Sent(val topic: Topic, val mascot: Mascot, val at: Instant, val opened: Boolean)
data class Nudge(val topic: Topic, val mascot: Mascot)
object NudgePlanner {
    fun plan(facts: Facts, rhythm: Rhythm, sent: List<Sent>, lastOpenAt: Instant?, now: Instant, zone: TimeZone): Nudge?
}
```
Порядок и числа — из спеки, раздел «Когда пишут»; общие запреты — Global Constraints. «Проигнорировано» — `opened == false` и отправлено больше часа назад; серия считается от самого нового. `LONG_GAP` говорит тот из Масла и Зёрнышка, кто писал на эту тему давнее.

- [ ] Step 1: `NudgePlannerTest` — по тесту на правило: тихие часы, «только что открывал», интервал 3 ч, два в день, тема раз в день, откат после 3 и 6 игноров, «пропал» только на 2/4/7/14-й день и кто пишет, пропущенный приём — хозяин обычной еды, долго без еды — не ближе двух часов ко сну и чередование, пустой день и серия, белок вечером от 25 г, «вчера в цель» не чаще раза в три дня, приоритет пропажи над остальным. FAIL.
- [ ] Step 2: реализовать; PASS.
- [ ] Step 3: commit `feat(mascot): планировщик — когда писать первыми и когда молчать`.

### Task 8: Мозг

**Files:**
- Create: `core/mascot/src/commonMain/kotlin/n7/kcalai/mascot/MascotBrain.kt`, `MascotEvent.kt`
- Test: `core/mascot/src/commonTest/kotlin/n7/kcalai/mascot/MascotBrainTest.kt`

**Interfaces — Produces:**
```kotlin
sealed interface MascotEvent {
    data class Poked(val mascot: Mascot, val count: Int) : MascotEvent
    data class Trick(val mascot: Mascot) : MascotEvent
    data class Held(val mascot: Mascot) : MascotEvent
    data class Dropped(val mascot: Mascot, val thrown: Boolean) : MascotEvent
    data class Petted(val mascot: Mascot) : MascotEvent
    data class Woken(val mascot: Mascot, val night: Boolean) : MascotEvent
    data class Ate(val entry: DiaryEntryEntity) : MascotEvent
    data object GoalReached : MascotEvent
    data class Returned(val awayMinutes: Long) : MascotEvent
    data class Opened(val messageId: Long) : MascotEvent
    data object Shaken : MascotEvent
}
enum class CueKind { PROUD, CELEBRATE, GREET, OPENED, DIZZY }
data class Cue(val kind: CueKind, val mascot: Mascot?)
data class Speech(val mascot: Mascot, val text: String)
data class Reaction(val cue: Cue?, val speech: Speech?)
data class Written(val message: MascotMessageEntity, val suggestion: Suggestion?, val thread: List<MascotMessageEntity>)

class MascotBrain(dao: MascotDao, facts: FactsBuilder, diaryDao: DiaryDao, zone: TimeZone, random: Random = Random.Default) {
    val profile: Flow<MascotProfileEntity>                       // строки нет — профиль по умолчанию
    fun messages(dateEpochDay: Long): Flow<List<MascotMessageEntity>>
    suspend fun write(now: Instant, notificationsEnabled: Boolean): Written?     // согласие, планировщик, сборка, запись NOTIFICATION
    suspend fun write(topic: Topic, mascot: Mascot, now: Instant): Written?      // явная тема (отладочный приёмник)
    suspend fun answer(topic: Topic, mascot: Mascot, now: Instant, food: String? = null): MascotMessageEntity?  // FEED
    suspend fun react(event: MascotEvent, now: Instant): Reaction?
    suspend fun onAppOpened(now: Instant)                        // markAllOpened + lastOpenAt
    suspend fun setName(name: String?); suspend fun setSex(sex: Sex); suspend fun setConsent(consent: WriteConsent)
}
```
Таблица `react`: Poked — реплика на 3-м (`POKE`) и 6-м (`POKE_ANNOYED`) тыке; Trick — `TRICK` с вероятностью 0,5; Held — `HELD` 0,7; Dropped — `THROWN`/`DROPPED` 0,6; Petted — `PETTED` всегда; Woken — ночью `WOKEN_NIGHT` всегда, днём `WOKEN_IDLE` 0,4; Ate — говорит хозяин еды, `Cue(PROUD, хозяин)`, тема по первому сработавшему: первая запись дня `MEAL_FIRST`, впервые в истории `MEAL_NEW`, третий день подряд и больше `MEAL_REPEAT`, 23:00–4:59 `MEAL_LATE`, иначе `MEAL_OWNER` с вероятностью 0,35; GoalReached — `CELEBRATE`, Масло или Яйцо, `GOAL_REACHED`; Returned — `GREET`, от 3 ч `GREET_HOURS`, от суток `GREET_DAYS`, говорящий по кругу; Opened — `markOpened`, `Cue(OPENED, автор)`, `OPENED`; Shaken — `DIZZY`, `SHAKEN`, не чаще раза в 10 секунд. Память облачков — последние 60 id в памяти; память сообщений — `lineIds` последних 40 из базы.

- [ ] Step 1: `MascotBrainTest`: `` `без согласия не пишет` ``, `` `при выключенных уведомлениях не пишет и не тратит бюджет` ``, `` `написанное ложится в базу с днём по местной зоне` `` (23:50 по Москве при UTC-часах), `` `второе сообщение не повторяет варианты первого` ``, `` `открытие приложения отмечает прочитанным и помнит время` ``, `` `третий тык — реплика, первый и второй — молча` ``, `` `еда — гордится хозяин, первая за день — с репликой` ``, `` `открыл из шторки — отмечено прочитанным, автор радуется` ``, `` `ответ на имя — по имени` ``. FAIL.
- [ ] Step 2: реализовать; PASS.
- [ ] Step 3: commit `feat(mascot): мозг — пишет, отвечает и реагирует`.

### Task 9: Персонажи в ленте

**Files:**
- Modify: `feature/diary/build.gradle.kts` (`api(project(":core:mascot"))`)
- Modify: `Feed.kt` (`FeedItem.Said`, `FeedItem.Ask`, `AskKind`, `interleave`, `gapPosition` по `List<FeedItem>`, `speakerOf`), `Bubbles.kt` (`SaidBubble`, `AskBubble`, тексты на «ты»), `Format.kt` (`outlineSentence`, `notFoundText`, `mealVerb` на «ты»), `DiaryViewModel.kt`, `DiaryScreen.kt`, `Sheets.kt` (`NameSheet`, `GoalSheet(onSex)`), `GoalHelper.kt`
- Modify: `DiaryHarness.kt` (+ `FakeMascotDao`, мозг), `app/.../AppContainer.kt` (`mascotBrain`), `MainActivity.kt` (разрешение на 33+)
- Test: `FeedTest`, `SpeakerTest`, `SummaryTest`, `FormatTest`, `DiaryViewModelTest`, `NameTest`

**Interfaces — Produces:**
```kotlin
data class Said(…) // FeedItem.Said(val id: Long, val mascot: Mascot, val text: String, val time: String, val minute: Int), key "said-$id"
enum class AskKind { NAME, WRITE }        // FeedItem.Ask(val kind: AskKind), key "ask-$kind"; NAME — Яйцо, WRITE — Зёрнышко
fun interleave(bubbles: List<FeedItem.Meal>, said: List<FeedItem.Said>, zone: TimeZone): List<FeedItem>
fun cleanName(text: String): String?      // trim, пробелы схлопнуть, ≤ 20, хоть одна буква
// DiaryViewModel(…, brain: MascotBrain, onRequestNotifications: () -> Unit = {}, random: Random = Random.Default, …)
fun onAskName(); fun onAnswerName(name: String?); fun onAnswerWrite(allow: Boolean); fun onNotificationsResult(granted: Boolean); fun onSex(sex: Sex)
// Overlay.Name
```
Вопрос имени: сегодня, есть хоть одна запись, `name == null && !nameAsked`. Вопрос о сообщениях: имя решено (`name != null || nameAsked`), `consent == UNASKED`, сегодня ≥ 2 записей. Вопросы стоят в конце ленты перед перебором/остатком.

- [ ] Step 1: тесты (сначала красные): `FeedTest` — `` `сообщение персонажа встаёт между пузырьками по времени` ``, `` `вопрос про обед считает только пузырьки` ``; `SpeakerTest` — `` `сообщение говорит его автор` ``, `` `имя спрашивает Яйцо, разрешение — Зёрнышко` ``; `SummaryTest`/`FormatTest` — тексты на «ты» без рода; `NameTest` — `cleanName`; `DiaryViewModelTest` — `` `сообщение из шторки стоит в ленте своего дня` ``, `` `после первой записи Яйцо спрашивает имя` ``, `` `«Не скажу» убирает вопрос насовсем` ``, `` `имя сохраняется, и Яйцо отвечает в ленте` ``, `` `после имени и двух записей Зёрнышко спрашивает, можно ли писать` ``, `` `«Пишите» спрашивает разрешение телефона и отвечает по итогу` ``, `` `«Не надо» — согласие отозвано, вопрос не возвращается` ``.
- [ ] Step 2: `.\gradlew.bat :feature:diary:testAndroidHostTest` — FAIL.
- [ ] Step 3: реализовать, связать в `AppContainer`/`MainActivity` (`ActivityResultContracts.RequestPermission`, ниже 33 — сразу `areNotificationsEnabled()`); манифест — `POST_NOTIFICATIONS`.
- [ ] Step 4: PASS; `.\gradlew.bat :app:assembleDebug` собирается.
- [ ] Step 5: commit `feat(diary): персонажи пишут в ленту, спрашивают имя и разрешение`.

### Task 10: Уведомления-переписки

**Files:**
- Create: `core/ui/src/commonMain/kotlin/n7/kcalai/ui/mascot/Portrait.kt` (`fun mascotPortrait(mascot: Mascot, sizePx: Int): ImageBitmap` — круг цвета плитки макроса, персонаж в покое)
- Create: `app/src/main/java/n7/kcalai/mascot/MascotNotifier.kt`, `MascotWorker.kt`, `LogSuggestionReceiver.kt`, `SuggestionExtras.kt`
- Create: `app/src/main/res/drawable/ic_stat_mascot.xml`
- Create: `app/src/debug/java/n7/kcalai/mascot/DebugWriteReceiver.kt`; Modify: `app/src/debug/AndroidManifest.xml`
- Modify: `app/src/main/AndroidManifest.xml` (receiver, `singleTop`), `KcalApp.kt` (расписание), `MainActivity.kt` (открытие из шторки, снятие уведомлений на возврате)
- Test: `app/src/test/java/n7/kcalai/mascot/SuggestionExtrasTest.kt`

**Interfaces — Produces:**
```kotlin
object SuggestionExtras {
    fun encode(s: Suggestion, messageId: Long, mascot: Mascot): Map<String, String>
    fun decode(extras: Map<String, String>): Triple<Suggestion, Long, Mascot>?
}
class MascotNotifier(context: Context) {
    fun ensureChannel()
    fun post(written: Written)                 // MessagingStyle, Person = персонаж, ярлык-переписка "mascot-<name>", тап → MainActivity с EXTRA_MESSAGE_ID, кнопка «Записать: …»
    fun postReply(mascot: Mascot, food: String, grams: Int, reply: MascotMessageEntity)
    fun cancelAll()
    companion object { const val EXTRA_MESSAGE_ID = "n7.kcalai.mascot.MESSAGE_ID" }
}
class MascotWorker : CoroutineWorker { companion object { fun schedule(context: Context) } }  // раз в 30 мин, KEEP
```
Проверка на эмуляторе: `adb -s emulator-5554 shell am broadcast -n n7.kcalai/.mascot.DebugWriteReceiver --es topic LONG_GAP --es mascot GRAIN`.

- [ ] Step 1: `SuggestionExtrasTest` — `` `подсказка переживает дорогу через PendingIntent` ``, `` `битые extras — null, а не падение` ``. `.\gradlew.bat :app:testDebugUnitTest` — FAIL.
- [ ] Step 2: реализовать всё перечисленное.
- [ ] Step 3: PASS; `:app:assembleDebug`.
- [ ] Step 4: эмулятор: поставить debug, разрешить, отладочным приёмником вызвать сообщение; снимок шторки — аватар, имя, текст, кнопка; «Записать» → запись в дневнике и ответ в шторке; тап → лента с сообщением.
- [ ] Step 5: commit `feat(app): персонажи пишут в шторку — переписка с аватаром и кнопкой «Записать»`.

### Task 11: Кукла — лицо, взгляд, действия

**Files:**
- Create: `core/ui/src/commonMain/kotlin/n7/kcalai/ui/mascot/Face.kt`, `Puppet.kt`, `Particles.kt`
- Modify: `Mascot.kt` (`MascotFigure(puppet, pose, modifier)`; жесты уходят в сцену), `Paper.kt` (`face` → `Face.kt`, поза `HELD` — обе руки вверх), `egg/Egg.kt`, `butter/Butter.kt`, `grain/Grain.kt` (тело без лица + геометрия лица)
- Test: `core/ui/src/commonTest/kotlin/n7/kcalai/ui/mascot/PuppetTest.kt`

**Interfaces — Produces:**
```kotlin
enum class MascotFace { CALM, HAPPY, SURPRISED, ASLEEP, DIZZY, BLISS, GRUMPY, YAWN, WORRIED }
enum class MascotAct(val durationMs: Int) { SHAKE, HOP, NOD, TRICK, DIZZY, YAWN, STARTLE, MELT, CELEBRATE, SULK, BLISS }
@Stable class MascotPuppet {
    val mascot: Mascot
    var rest: MascotFace                 // CALM или ASLEEP — к чему возвращается лицо
    var look: Offset                     // цель взгляда, компоненты −1…1
    suspend fun play(act: MascotAct)     // отменяет прошлое действие
    suspend fun blink()
    suspend fun breathe(times: Int)
    suspend fun wave(times: Int)
}
@Composable fun rememberMascotPuppet(mascot: Mascot): MascotPuppet
@Composable fun MascotFigure(puppet: MascotPuppet, pose: MascotPose, modifier: Modifier = Modifier)
internal fun eyeShift(look: Offset): Offset   // не дальше 2,5 единицы холста
```

- [ ] Step 1: `PuppetTest`: `` `каждое действие конечно и короче трёх секунд` ``, `` `взгляд не уводит зрачки за край глаза` ``. FAIL.
- [ ] Step 2: реализовать; PASS; `:feature:diary` компилируется со сценой на новом API.
- [ ] Step 3: commit `feat(mascot): лицо с выражениями, взгляд, моргание и конечные действия`.

### Task 12: Сцена — жесты, взгляд, сон, облачка, реакции

**Files:**
- Create: `feature/diary/.../PokeMeter.kt`, `Drowse.kt`, `Gaze.kt`, `SpeechBubbles.kt`, `MascotGestures.kt`
- Modify: `MascotStage.kt`, `DiaryScreen.kt` (наблюдатель касаний в `PointerEventPass.Initial`), `DiaryViewModel.kt` (`speech`, `cue`, `night` в `DiaryUiState`; `onMascotEvent`, `onOpenedFromMessage`, `onPhoneShaken`; реакции на добавление и закрытие цели; встреча на возврате)
- Test: `PokeMeterTest`, `DrowseTest`, `GazeTest`, `SpeechPlacementTest`, `DiaryViewModelTest`

**Interfaces — Produces:**
```kotlin
data class Poke(val count: Int, val trick: Boolean)
class PokeMeter(windowMs: Long = 4_000, doubleTapMs: Long = 300) { fun poke(atMs: Long): Poke }
enum class Rest { AWAKE, DROWSY, ASLEEP }
fun restAfterIdle(idleMs: Long): Rest                 // < 40 с AWAKE, < 60 с DROWSY, дальше ASLEEP
fun isNight(hour: Int): Boolean                        // 23…5
fun gaze(from: Offset, to: Offset, reachPx: Float): Offset   // длина ≤ 1
data class SpeechPlacement(val offset: IntOffset, val tailUp: Boolean, val tailX: Int)
fun speechPlacement(anchor: Rect, bubble: IntSize, bounds: IntSize, marginPx: Int): SpeechPlacement
data class SpeechUi(val id: Long, val mascot: Mascot, val text: String)
data class CueUi(val id: Long, val kind: CueKind, val mascot: Mascot?)
fun speechDurationMs(text: String): Long              // 2500 + 40 на символ, не больше 5000
```
Сцена: палец — взгляд всех троих (на отпускание через 700 мс — прямо); набор текста — взгляд вниз на 1,2 с; тап — `SHAKE` (+ лёгкая вибрация), двойной — `TRICK`, 6-й тап — `SULK`; долгое нажатие — поза `HELD`, следует за пальцем, наклон от скорости, отпустил — пружина домой, быстрый бросок — `DIZZY`; поглаживание (путь пальца ≥ полторы ширины) — `BLISS` (Масло — `MELT`); бездействие — 40 с зевок одного, 60 с сон всех; ночь — сон сразу, касание будит (`STARTLE`); моргание — случайно в течение 12 с после события; `cue`: PROUD → `HOP` хозяина и взгляд остальных на ленту, CELEBRATE → `CELEBRATE` по очереди, GREET → `HOP` по очереди, OPENED → `HOP` автора, DIZZY → `DIZZY` у всех. Облачко — над персонажем (под ним, если не влезает), живёт `speechDurationMs`.

- [ ] Step 1: тесты (красные): `PokeMeterTest` — счёт в окне, сброс после паузы, двойной тап, спам из 100 тапов не ломает счёт (не больше 6 после насыщения… счётчик продолжает расти, реплики только на 3 и 6); `DrowseTest`; `GazeTest`; `SpeechPlacementTest` (над, под у верхнего края, прижат к бокам); `DiaryViewModelTest` — `` `третий тык — облачко, и оно уходит само` ``, `` `запись еды — хозяин гордится` ``, `` `цель закрыта точно — праздник` ``, `` `вернулся через пять часов — встречают` ``, `` `открыл из шторки — автор радуется, сообщение прочитано` ``, `` `тряска — у всех кружится голова, не чаще раза в десять секунд` ``.
- [ ] Step 2: FAIL; реализовать; PASS.
- [ ] Step 3: эмулятор: снимки — взгляд за пальцем, облачко, сон, перетаскивание.
- [ ] Step 4: commit `feat(diary): персонажи следят за пальцем, играют, засыпают и отвечают облачками`.

### Task 13: Тряхнуть телефон

**Files:**
- Create: `app/src/main/java/n7/kcalai/mascot/ShakeDetector.kt` (чистый: `fun onSample(x: Float, y: Float, z: Float, atMs: Long): Boolean` — ускорение без g > 13 м/с² трижды за 600 мс, затем пауза 1,5 с), `PhoneShakes.kt` (`callbackFlow` на акселерометре)
- Modify: `MainActivity.kt` (слушать только в `RESUMED`, `vm.onPhoneShaken()`)
- Test: `app/src/test/java/n7/kcalai/mascot/ShakeDetectorTest.kt`

- [ ] Step 1: `ShakeDetectorTest` — `` `спокойно лежит — не тряска` ``, `` `три рывка подряд — тряска` ``, `` `после тряски полторы секунды тишины` ``. FAIL.
- [ ] Step 2: реализовать; PASS.
- [ ] Step 3: commit `feat(app): тряхнул телефон — у персонажей кружится голова`.

### Task 14: Документы и итоговая проверка

**Files:**
- Modify: `README.md` (модуль `:core:mascot`, строки в таблице тестов, абзац про персонажей и уведомления), `docs/privacy-policy.md` (на устройстве: имя, пол из помощника цели, сообщения персонажей; уведомления собираются на телефоне; новая дата), `todo.md` (банк реплик сделан; дальше — модель на устройстве поверх движка)

- [ ] Step 1: правки документов.
- [ ] Step 2: `.\gradlew.bat testAndroidHostTest :app:testDebugUnitTest :app:assembleDebug` — всё зелёное.
- [ ] Step 3: эмулятор — сквозной сценарий: первая запись → вопрос имени → имя → ответ; вторая запись → разрешение; отладочное сообщение в шторке; «Записать»; облачка; сон.
- [ ] Step 4: commit `docs: живые персонажи — README, политика, todo`.
