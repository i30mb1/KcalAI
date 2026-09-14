# Модели OCR

Детектор и распознаватель PP-OCRv5 в `core/ocr/src/main/assets/models` собраны
конвейером апстрима (iFleey/PPOCRv5-Android): Paddle → ONNX (paddle2onnx) →
onnxsim со статической формой → onnx2tf → TFLite FP16.

Конвейер живёт в docker-образе `ppocr-env` (Python 3.10, paddlepaddle,
paddle2onnx, onnxsim, onnx2tf 1.22.3, tensorflow 2.15) — на Windows
с Python 3.14 он не ставится. Рабочий каталог с уже скачанными моделями
и ONNX (`models_tmp/ocr_det_v5.onnx`) монтируется в `/work`.

## Детектор под другой размер входа

Размер входа зашит в модель, а код читает его из тензора. Кадр анализа —
960×1280, и детектор 1280×960 (ВЫСОТА×ШИРИНА) видит его 1:1; при 640×640
кадр ужимался вдвое, и мелкий шрифт этикетки не находился.

```
docker run --rm -v "<каталог с models_tmp>:/work" -e SIZES=1280x960 ppocr-env:latest bash /work/convert_det.sh
```

Результат — `out_det/<размер>/ocr_det_fp16.tflite`; положить в assets
и обновить `sha256`, `sizeBytes`, `inputShape` в `manifest.json`.
Размеры кратны 32. Замер на Nothing A024 (CPU): 640×640 — 115 мс,
960×736 — 200 мс, 1280×960 — 340 мс на кадр.
