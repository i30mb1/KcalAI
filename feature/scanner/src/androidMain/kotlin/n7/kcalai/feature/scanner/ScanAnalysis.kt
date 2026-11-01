package n7.kcalai.feature.scanner

import android.content.Context
import android.os.Process
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import n7.kcalai.ocr.LabelOcr

/**
 * Разбор кадров окна съёмки: штрих-код, распознавание текста, согласие кадров
 * и запись кадров на диск.
 *
 * Собирается один раз за окно и закрывается вместе с ним. Окно переходит от кода
 * к карточке, не пересоздавая ни камеру, ни анализ: меняется только то, что
 * делается с кадром, — см. [reading]. Пересоздание значило бы переконфигурацию
 * камеры, а это рывок картинки ровно тогда, когда человек держит пачку в кадре.
 *
 * Всё тяжёлое — на потоках пониженного приоритета: распознавание занимает сотни
 * миллисекунд и грузит все ядра, а конкурировать за процессор с отрисовкой превью
 * ему незачем. Кадром позже разбор ничего не теряет, дёрганое превью — теряет наводку.
 *
 * @param reading читать этикетку; `false` — только искать штрих-код
 * @param onBarcode код с кадра — на главном потоке
 * @param onFrame разобранный кадр этикетки — на главном потоке
 * @param outcome чем кончилась съёмка — для записи на диск при закрытии.
 *        `null` — карточки не было, и записывать нечего.
 */
internal fun scanAnalysis(
    context: Context,
    reading: () -> Boolean,
    onBarcode: (String) -> Unit,
    onFrame: (LabelFrame) -> Unit,
    outcome: () -> String?,
): FrameAnalysis {
    val executor = Executors.newSingleThreadExecutor(unhurried("label-frame"))
    val inference = Executors.newSingleThreadExecutor(unhurried("label-ocr"))
    val dispatcher = inference.asCoroutineDispatcher()

    // Движок распознавания поднимается при первом кадре этикетки, а не здесь:
    // на шаге кода он не нужен вовсе.
    val ocr = LabelOcr(context, dispatcher)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val recorder = LabelRecorder(LabelRecorder.directory(context))
    val startedAt = System.currentTimeMillis()
    val main = ContextCompat.getMainExecutor(context)

    val analyzer = LabelAnalyzer(
        ocr = ocr,
        scope = scope,
        recorder = recorder,
        reading = reading,
        onBarcode = { code -> main.execute { onBarcode(code) } },
        onFrame = { read -> main.execute { onFrame(read) } },
    )

    val useCase = ImageAnalysis.Builder()
        // Разрешение анализа по умолчанию — примерно 640×480, и на таблице пищевой
        // ценности этого не хватает: подписи там набраны шестым кеглем, и в кадре
        // от буквы остаётся два-три пикселя. Детектор строку находит, распознавание
        // отдаёт по ней кашу. Просить больше 1280×960 незачем — инференс растёт
        // квадратично, а разборчивее уже не становится. Штрих-коду на шаге кода
        // то же разрешение только на пользу: код с полки читается издалека.
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 960),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                )
                .build()
        )
        // RGBA напрямую от камеры: иначе кадр приходит в YUV, и `toBitmap()`
        // перекладывает его в цвет сам, на каждом кадре и на потоке анализа.
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        // Поворот делает камера. Сенсор смонтирован боком почти во всех телефонах,
        // и без этого в анализ уходит лежащий на боку кадр: детектор его размечает,
        // а распознающая модель обучена на горизонтальных строках и отдаёт мусор.
        // Разворачивать своим `Canvas` — лишняя копия на пять мегабайт на кадре.
        .setOutputImageRotationEnabled(true)
        .build()
        .apply { setAnalyzer(executor, analyzer) }

    return FrameAnalysis(useCase) {
        scope.cancel()
        // Нативные сессии надо отпустить: они держат модели в памяти. Закрытие
        // OCR встаёт в ту же очередь, что и кадры, поэтому executor гасится
        // мягко — уже поставленное успеет доработать.
        ocr.close()
        analyzer.close()
        // Итог снимается здесь: к моменту закрытия окна поля содержат то,
        // с чем человек согласился, — а расхождение с тем, что предлагала
        // камера, и есть материал для правки разбора.
        outcome()?.let { result ->
            // Запись — не на этом потоке: там несколько мегабайт, а мы на главном.
            Thread { recorder.save(startedAt, result) }.start()
        }
        executor.shutdown()
        inference.shutdown()
    }
}

/**
 * Поток, который уступает дорогу интерфейсу.
 *
 * Приоритет ниже обычного, но выше «фонового»: `THREAD_PRIORITY_BACKGROUND`
 * отправляет поток в background-cgroup, а там система выдаёт ему считанные
 * проценты процессора — распознавание кадра растянулось бы на секунды.
 * Нужна ровно уступка отрисовке, а не ссылка в каменоломню.
 */
private fun unhurried(name: String) = ThreadFactory { runnable ->
    Thread({
        Process.setThreadPriority(FRAME_THREAD_PRIORITY)
        runnable.run()
    }, name)
}

/** Ниже интерфейсного нуля, выше фоновой десятки. */
private const val FRAME_THREAD_PRIORITY = 4
