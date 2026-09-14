# Kcal AI — локальный сервер

Сервер на компьютере разработчика, к которому телефон стучится по локальной сети.
Три задачи: отдавать товары по штрих-коду, раздавать свежий `seed.db` и принимать
то, что телефон собирает, — вклады и сессии съёмки этикеток. Ничего не сливает
автоматически: собранное лежит отдельно и попадает в справочник руками, после анализа.

Контракт `GET /v1/products/{gtin}` и `POST /v1/contributions` — из `docs/server-contract.md`,
клиент против них уже написан. Остальное добавляется.

## Стек и расположение

- `server/` — отдельный Gradle-проект со своим `settings.gradle.kts` и wrapper'ом.
  В Android-сборку не включается: AGP 9 со встроенным Kotlin и `kotlin("jvm")`
  в одном билде конфликтуют. `:core:model` не шарится — четыре поля дешевле продублировать.
- Kotlin, Ktor (Netty, ContentNegotiation + kotlinx.serialization), `sqlite-jdbc`.
- Запуск: `cd server && ./gradlew run` → `0.0.0.0:8080`. Порт и каталог данных —
  переменные `KCAL_PORT`, `KCAL_DATA` (по умолчанию `8080`, `server/data`).
- `server/data/` в `.gitignore`: `kcal-server.db`, `seed/seed.db`, `scans/`.

## Эндпоинты

### `GET /v1/products/{gtin}`

По контракту. Читает таблицу `product` — то, что разработчик положил туда сам.
`gtin` проверяется контрольной цифрой (EAN-8/13); не прошёл — 400. Нет строки — 404 без тела.

### `POST /v1/contributions`

По контракту. Каждый элемент массива проверяется теми же инвариантами, что
`NutrimentValidator` на клиенте (ккал 0..900, макросы 0..10000 сг, сумма ≤ 10000,
контрольная цифра GTIN, непустое `name`). Прошедшие пишутся в `contribution`
**все, без дедупа** — это голоса, и повтор того же GTIN штатен. Ответ
`202 {"accepted": [gtin…]}` — прошедшие проверку. Порог согласия не реализуется:
публикация в `product` — ручная.

### `GET /v1/seed/manifest.json`

```json
{ "version": 3, "sha256": "…", "size": 1843200, "url": "/v1/seed/seed.db" }
```

`version` — из `server/data/seed/version.txt` (кладёт `build_seed.py --install`
вместе с `seed.db` и `seed.db.sha256`). Файла нет — 404.

### `GET /v1/seed/seed.db`

Сам файл, `application/octet-stream`, с `Content-Length`.

### `POST /v1/scans`

`multipart/form-data`: поле `readings` (текст `readings.txt`) и поля `frame`
(файлы `frame-NN.jpg`, имя файла сохраняется). Заголовок `X-Scan-Id` — имя сессии
(`scan-<ms>`), только `[a-z0-9-]`, иначе 400. Ложится в `server/data/scans/<scan-id>/`.
Если каталог уже есть — `200` без перезаписи (загрузка идемпотентна). Успех — `201`.

## Схема `kcal-server.db`

```sql
CREATE TABLE product (
  gtin TEXT PRIMARY KEY, name TEXT NOT NULL, brand TEXT,
  kcal100 INTEGER NOT NULL, prot100 INTEGER NOT NULL,
  fat100 INTEGER NOT NULL, carb100 INTEGER NOT NULL,
  serving_g INTEGER, updated_at INTEGER NOT NULL
);
CREATE TABLE contribution (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  gtin TEXT NOT NULL, name TEXT NOT NULL,
  kcal100 INTEGER NOT NULL, prot100 INTEGER NOT NULL,
  fat100 INTEGER NOT NULL, carb100 INTEGER NOT NULL,
  serving_g INTEGER, created_at INTEGER NOT NULL, received_at INTEGER NOT NULL
);
CREATE INDEX contribution_gtin ON contribution(gtin);
```

Единицы — как в контракте: ккал целые, макросы в сотых грамма.

## Инструменты анализа

- `tools/builddb/build_seed.py --install` дополнительно копирует `seed.db`,
  `seed.db.sha256` и пишет `version.txt` (инкремент) в `server/data/seed/`.
- `server/tools/publish.py <gtin>` — переносит медиану вкладов по GTIN в `product`
  (или принимает значения аргументами). Стандартная библиотека Python, как остальные скрипты.

## Клиент

1. **Адрес сервера.** `AppContainer.SERVER_BASE_URL` → `BuildConfig.KCAL_SERVER`,
   значение из `local.properties` (`kcal.server=http://192.168.1.10:8080`), пусто — источника нет.
   Debug-манифест получает `network_security_config` с `cleartextTrafficPermitted`
   для локальных подсетей; release — без изменений.
2. **Обновление справочника.** Сеть живёт в `:core:remote`:
   `KcalServerSource.seedManifest()` + `downloadSeed(to: File)`. `SeedUpdateWorker`
   (`WorkManager`, сеть обязательна, unique KEEP, ставится при старте) сравнивает
   sha256 манифеста со stamp'ом, качает во временный файл, проверяет хеш и кладёт
   `filesDir/seed.db.next` + `seed.db.next.sha256`. Под открытым соединением `FoodDb`
   база не подменяется: `SeedInstaller.install` при следующем старте видит `.next`,
   переименовывает его в `seed.db` и пишет stamp. Логика «есть `.next` → продвинуть»
   выносится в чистую функцию над `File`, чтобы тестироваться на JVM.
3. **Отправка сессий съёмки.** После `LabelRecorder.save` — `ScanUploadWorker.enqueue`.
   Воркер обходит `label-scans/scan-*` без маркера `.uploaded`, шлёт multipart,
   при 200/201 ставит маркер. Прополка «последние 5» остаётся; помеченные
   удаляются первыми.

## Ошибки

Сервер: невалидный JSON — 400 с текстом; ошибка БД — 500 и лог. Клиент: любой сбой
сети — молчаливый `retry()` у воркеров, как у `ContributionWorker`; обновление
справочника никогда не ломает уже установленный `seed.db`.

## Тесты

- Сервер: Ktor `testApplication` на каждый эндпоинт — валидный/невалидный GTIN,
  404, приём вкладов с отбраковкой, манифест без файла, идемпотентная загрузка сессии.
- Клиент: продвижение `.next` в `SeedInstaller` (JVM, временный каталог);
  разбор манифеста в `KcalServerSource`.

## Вне scope

Порог согласия, веб-поиск, полный срез `food.db`, аутентификация, HTTPS.
