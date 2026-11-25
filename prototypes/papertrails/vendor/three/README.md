# three.js 0.187.0 — в проекте, чтобы мир прогулки работал без интернета

Страница дневника отдаётся с телефона по домашней сети, и мир прогулки не должен ходить за библиотекой в интернет.
Файлы — без изменений из официального пакета `three@0.187.0` (npm, через jsdelivr):

- `build/three.module.js`, `build/three.core.js` — минифицированные сборки (`three.module.min.js`, `three.core.min.js`);
  имя ядра — без `.min`: так его импортирует модуль.
- `examples/jsm/loaders/GLTFLoader.js` и его `examples/jsm/utils/BufferGeometryUtils.js`, `SkeletonUtils.js`.

Подключение — import map в `kcal.html` и `feature/web/src/wasmJsMain/resources/index.html`. Лицензия — `LICENSE` (MIT).
