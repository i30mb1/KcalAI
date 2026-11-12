# Политика конфиденциальности Kcal AI

Действует с 12 ноября 2026 года. Приложение: Kcal AI (`n7.kcalai`) для Android.

## Коротко

Регистрации нет. Дневник питания, цели, вес и переписка с персонажами хранятся только на телефоне. Заведённый вами продукт уходит в общую базу, только если вы согласитесь на предложение Яйца. Мы не продаём данные, не показываем рекламу и не передаём ваш дневник никому.

## Что хранится на устройстве

- записи дневника: продукты, граммы, время, калории и БЖУ;
- дневная цель, вес тела, заведённые вами продукты;
- история выбора в подсказках: по ней приложение учится предлагать ваши обычные продукты и порции. Обучение идёт на телефоне;
- имя, если вы назвали его персонажам, и пол, если вы указали его в помощнике цели: по ним персонажи обращаются к вам и правильно склоняют слова;
- сообщения персонажей и отметка, что вы их видели: по ним персонажи не повторяются и пишут реже, если вы не отвечаете;
- кадры последних пяти съёмок этикеток вместе с распознанным текстом. Они нужны, чтобы разбирать ошибки распознавания, лежат во внутренней памяти приложения, никуда не отправляются и не попадают в резервную копию.

Если на телефоне включено резервное копирование Android, система может сохранить дневник, цели, продукты и переписку с персонажами в вашем аккаунте Google по своим правилам. Кадры этикеток в копию не входят.

Всё это удаляется вместе с приложением или кнопкой «Очистить данные» в настройках Android.

## Камера

Камера нужна для сканирования штрих-кодов и таблиц пищевой ценности. Изображение обрабатывается на телефоне, в интернет кадры не уходят. Без разрешения на камеру приложение работает, а штрих-код можно ввести цифрами.

## Уведомления

Яйцо, Масло и Зёрнышко пишут первыми, только если вы разрешили это в ленте («Пишите») и в настройках телефона. Сообщения собираются на телефоне по вашему дневнику — для этого ничего не отправляется в интернет. Отказаться можно ответом «Не надо» в ленте или в настройках уведомлений Android.

## Датчик движения

Пока дневник открыт на экране, приложение слушает акселерометр, чтобы заметить, что телефон тряхнули, — тогда у персонажей кружится голова. Показания не сохраняются и никуда не отправляются.

## Что уходит в интернет

- **Open Food Facts.** Если штрих-кода нет в справочнике на телефоне, приложение запрашивает его в открытой базе Open Food Facts (world.openfoodfacts.org). Отправляется только номер штрих-кода. Как любой веб-сервер, Open Food Facts видит IP-адрес запроса; его политика — https://world.openfoodfacts.org/privacy.
- **ВкусВилл.** Если калорийности товара не нашлось и в Open Food Facts, номер штрих-кода запрашивается в каталоге ВкусВилла (mcp.vkusvill.ru). Отправляется только номер штрих-кода. Как любой веб-сервер, ВкусВилл видит IP-адрес запроса.
- **Google ML Kit.** Сканер штрих-кодов построен на ML Kit от Google, как и нейросеть Gemini Nano, которая на поддерживающих её телефонах разбирает непонятые фразы, отвечает на вопросы о дневнике и помогает прочитать этикетку. Распознавание и нейросеть работают на телефоне: ваши фразы, вопросы, дневник и кадры с камеры в интернет не уходят. Но ML Kit отправляет в Google технические сведения: модель устройства и версию Android, версию приложения, идентификатор установки (не привязан к человеку), время работы и коды ошибок. Google использует их для диагностики и не передаёт третьим лицам. Подробнее: https://developers.google.com/ml-kit/android-data-disclosure.

- **Сервер Kcal AI.** Если штрих-кода нет в справочнике на телефоне, его номер сначала запрашивается у нашего сервера: там лежат продукты, которых нет в Open Food Facts. С этого же сервера приложение скачивает обновления справочника. Когда вы заводите продукт со штрих-кодом, Яйцо предлагает отправить его в общую базу, чтобы его находили и другие. Только если вы нажмёте «Отправить», на сервер уходят штрих-код, название, калорийность, белки, жиры, углеводы и порция продукта. Ни дневник, ни имя, ни кадры с камеры не отправляются, и продукт не связан с вами или вашим устройством. Соединение шифруется (HTTPS). Сервер видит IP-адрес запроса и не хранит его вместе с продуктом. Присланные продукты разработчик проверяет и публикует, чтобы приложение находило их по штрих-коду у всех.

Больше приложение ничего не отправляет. Аналитики, рекламы и трекеров нет.

## Дети

Приложение не предназначено для детей младше 13 лет и не собирает о них данные.

## Изменения

Новая редакция появится на этой странице с новой датой вверху.

## Связь

Вопросы и запросы — в разделе Issues репозитория проекта: https://github.com/i30mb1/KcalAI/issues.

---

# Kcal AI Privacy Policy (English)

Effective November 12, 2026. App: Kcal AI (`n7.kcalai`) for Android.

There is no sign-up. Your food diary, goals and body weight are stored only on your device. A custom food goes to the shared database only if you accept the Egg's offer to send it. We do not sell data, show ads, or share your diary with anyone.

**Stored on the device:** diary entries, daily goal, body weight, custom foods, and your suggestion choices (used for on-device personalization). The frames from your last five nutrition-label scans and their recognized text are also kept for diagnosing recognition errors. They stay in the app's internal storage, are never uploaded, and are excluded from backups. If Android backup is enabled, the system may back up your diary, goals and custom foods to your Google account. Uninstalling the app or clearing its data deletes everything.

**Camera:** used to scan barcodes and nutrition labels. Images are processed on the device and never uploaded. The app works without camera permission; you can type a barcode instead.

**Network:**
- *Open Food Facts.* When a barcode is not in the on-device database, only the barcode number is sent to Open Food Facts (world.openfoodfacts.org), which, like any web server, sees the request's IP address.
- *VkusVill.* When Open Food Facts has no nutrition values for the product either, only the barcode number is sent to the VkusVill catalogue (mcp.vkusvill.ru), which, like any web server, sees the request's IP address.
- *Google ML Kit.* The barcode scanner uses Google ML Kit, and so does Gemini Nano, the on-device model that, on phones that support it, parses phrases the search did not understand, answers questions about the diary and helps read nutrition labels. Scanning and the model run on the device: your phrases, questions, diary and camera frames do not leave it. But ML Kit sends Google device and app information, a per-installation identifier not tied to a person, performance metrics and error codes for diagnostics and analytics. This data is not shared with third parties. See https://developers.google.com/ml-kit/android-data-disclosure.

- *Kcal AI server.* When a barcode is not in the on-device database, its number is first looked up on our server, which holds products missing from Open Food Facts; the app also downloads database updates from it. When you add a food with a barcode, the Egg offers to send it to the shared database so others can find it. Only if you tap "Send" does the app upload the barcode, name, calories, protein, fat, carbs and serving size. Your diary, name and camera frames are never sent, and the product is not linked to you or your device. The connection is encrypted (HTTPS). The server sees the request's IP address and does not store it with the product. The developer reviews submitted products and publishes them so the app can find them by barcode for everyone.

Nothing else leaves the device. No analytics, ads or trackers.

**Children:** the app is not directed at children under 13.

**Contact:** https://github.com/i30mb1/KcalAI/issues
