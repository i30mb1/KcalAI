package n7.kcalai.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import n7.kcalai.model.TextLine

/**
 * Распознавание текста на этикетке — единственная точка, через которую
 * приложение видит нативный движок.
 *
 * Всё здесь построено вокруг одного требования: **OCR может быть недоступен,
 * и это нормальный режим работы, а не сбой.** Нативная библиотека собрана
 * под arm64-v8a и x86_64, на armeabi-v7a её просто нет; модели могут не
 * распаковаться; движок может не подняться на конкретном устройстве. В любом
 * из этих случаев [read] возвращает `null`, съёмка этикетки продолжает работать
 * по арифметическому разбору цифр, и человек ничего не теряет, кроме названия.
 *
 * Поэтому же ошибки логируются один раз, а не на каждом кадре: недоступность —
 * это состояние, а не поток происшествий.
 *
 * @param dispatcher обязан быть однопоточным: движок держит нативную сессию,
 *        а она не рассчитана на параллельные вызовы.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LabelOcr(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : AutoCloseable {

    private val mutex = Mutex()
    private var engine: OcrEngine? = null
    private var unavailable = false

    /**
     * Снимок -> распознанные строки с геометрией.
     *
     * @return `null`, если OCR на этом устройстве недоступен. Пустой список
     *         означает «движок работает, но текста не увидел» — это разные
     *         вещи, и различать их обязательно: в первом случае надо молча
     *         работать по-старому, во втором — сказать человеку, что не вышло.
     */
    suspend fun read(bitmap: Bitmap): List<TextLine>? = withContext(dispatcher) {
        val active = engineOrNull() ?: return@withContext null
        try {
            active.process(bitmap).map { it.toTextLine() }
        } catch (error: Throwable) {
            // Сюда попадает и нехватка памяти на большом снимке. Один кадр
            // не должен уносить экран, а следующий может пройти.
            Log.e(TAG, "распознавание кадра не удалось", error)
            emptyList()
        }
    }

    /** Готов ли движок. Нужен UI, чтобы не обещать человеку того, чего не будет. */
    suspend fun isAvailable(): Boolean = withContext(dispatcher) { engineOrNull() != null }

    private suspend fun engineOrNull(): OcrEngine? = mutex.withLock {
        engine?.let { return it }
        if (unavailable) return null

        val created = try {
            OcrEngine.create(context).getOrThrow()
        } catch (error: Throwable) {
            // UnsatisfiedLinkError на неподдержанном ABI, отсутствие моделей,
            // отказ движка — для вызывающего это одно и то же событие, и
            // различать их перед ним незачем.
            Log.w(TAG, "OCR недоступен, работаем без распознавания текста", error)
            unavailable = true
            return null
        }
        engine = created
        created
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        const val TAG = "LabelOcr"
    }
}

/**
 * Повёрнутый бокс движка -> прямая рамка разбора.
 *
 * Угол отбрасывается намеренно. Таблица пищевой ценности читается по строкам
 * и колонкам, то есть по горизонталям и вертикалям; наклон в несколько градусов
 * от того, что пачку держали криво, на эту логику не влияет, а тащить его дальше
 * значило бы усложнять разбор ради величины, которой он не пользуется.
 */
private fun OcrResult.toTextLine(): TextLine = TextLine(
    text = text,
    centerX = centerX,
    centerY = centerY,
    width = width,
    height = height,
    confidence = confidence,
)
