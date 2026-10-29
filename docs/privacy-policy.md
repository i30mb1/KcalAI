# Политика конфиденциальности Kcal AI

Действует с 29 октября 2026 года. Приложение: Kcal AI (`n7.kcalai`) для Android.

## Коротко

Регистрации нет. Дневник питания, цели, вес и ваши продукты хранятся только на телефоне. Мы не продаём данные, не показываем рекламу и не передаём ваш дневник никому.

## Что хранится на устройстве

- записи дневника: продукты, граммы, время, калории и БЖУ;
- дневная цель, вес тела, заведённые вами продукты;
- история выбора в подсказках: по ней приложение учится предлагать ваши обычные продукты и порции. Обучение идёт на телефоне;
- кадры последних пяти съёмок этикеток вместе с распознанным текстом. Они нужны, чтобы разбирать ошибки распознавания, лежат во внутренней памяти приложения, никуда не отправляются и не попадают в резервную копию.

Если на телефоне включено резервное копирование Android, система может сохранить дневник, цели и продукты в вашем аккаунте Google по своим правилам. Кадры этикеток в копию не входят.

Всё это удаляется вместе с приложением или кнопкой «Очистить данные» в настройках Android.

## Камера

Камера нужна для сканирования штрих-кодов и таблиц пищевой ценности. Изображение обрабатывается на телефоне, в интернет кадры не уходят. Без разрешения на камеру приложение работает, а штрих-код можно ввести цифрами.

## Что уходит в интернет

- **Open Food Facts.** Если штрих-кода нет в справочнике на телефоне, приложение запрашивает его в открытой базе Open Food Facts (world.openfoodfacts.org). Отправляется только номер штрих-кода. Как любой веб-сервер, Open Food Facts видит IP-адрес запроса; его политика — https://world.openfoodfacts.org/privacy.
- **Google ML Kit.** Сканер штрих-кодов построен на ML Kit от Google. Распознавание идёт на телефоне, но ML Kit отправляет в Google технические сведения: модель устройства и версию Android, версию приложения, идентификатор установки (не привязан к человеку), время работы и коды ошибок. Google использует их для диагностики и не передаёт третьим лицам. Подробнее: https://developers.google.com/ml-kit/android-data-disclosure.

Больше приложение ничего не отправляет. Аналитики, рекламы и трекеров нет.

## Дети

Приложение не предназначено для детей младше 13 лет и не собирает о них данные.

## Изменения

Новая редакция появится на этой странице с новой датой вверху.

## Связь

Вопросы и запросы — в разделе Issues репозитория проекта: https://github.com/i30mb1/KcalAI/issues.

---

# Kcal AI Privacy Policy (English)

Effective October 29, 2026. App: Kcal AI (`n7.kcalai`) for Android.

There is no sign-up. Your food diary, goals, body weight and custom foods are stored only on your device. We do not sell data, show ads, or share your diary with anyone.

**Stored on the device:** diary entries, daily goal, body weight, custom foods, and your suggestion choices (used for on-device personalization). The frames from your last five nutrition-label scans and their recognized text are also kept for diagnosing recognition errors. They stay in the app's internal storage, are never uploaded, and are excluded from backups. If Android backup is enabled, the system may back up your diary, goals and custom foods to your Google account. Uninstalling the app or clearing its data deletes everything.

**Camera:** used to scan barcodes and nutrition labels. Images are processed on the device and never uploaded. The app works without camera permission; you can type a barcode instead.

**Network:**
- *Open Food Facts.* When a barcode is not in the on-device database, only the barcode number is sent to Open Food Facts (world.openfoodfacts.org), which, like any web server, sees the request's IP address.
- *Google ML Kit.* The barcode scanner uses Google ML Kit. Scanning runs on the device, but ML Kit sends Google device and app information, a per-installation identifier not tied to a person, performance metrics and error codes for diagnostics and analytics. This data is not shared with third parties. See https://developers.google.com/ml-kit/android-data-disclosure.

Nothing else leaves the device. No analytics, ads or trackers.

**Children:** the app is not directed at children under 13.

**Contact:** https://github.com/i30mb1/KcalAI/issues
