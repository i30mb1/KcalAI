package n7.kcalai.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

    /**
     * Под ним живёт всё обращение к нативной сессии — и счёт кадра, и её
     * освобождение. Обычный замок, а не корутинный: закрытие приходит с чужого
     * потока, и оно обязано либо дождаться кадра, либо встать в очередь за ним.
     */
    private val nativeLock = Any()

    /** Своя очередь для закрытия: она же очередь кадров — см. [close]. */
    private val closer = CoroutineScope(dispatcher)

    private var engine: OcrEngine? = null
    private var unavailable = false

    /**
     * Закрытие уже объявлено. Волатильно и проверяется до замка: кадры, успевшие
     * встать в очередь позже, не должны поднимать движок заново.
     */
    @Volatile
    private var closed = false

    /**
     * Снимок -> распознанные строки с геометрией.
     *
     * @return `null`, если OCR на этом устройстве недоступен. Пустой список
     *         означает «движок работает, но текста не увидел» — это разные
     *         вещи, и различать их обязательно: в первом случае надо молча
     *         работать по-старому, во втором — сказать человеку, что не вышло.
     */
    suspend fun read(bitmap: Bitmap): List<TextLine>? = withContext(dispatcher) {
        synchronized(nativeLock) {
            val active = engineOrNull() ?: return@synchronized null
            try {
                active.process(bitmap).map { it.toTextLine() }
            } catch (error: Throwable) {
                // Сюда попадает и нехватка памяти на большом снимке. Один кадр
                // не должен уносить экран, а следующий может пройти.
                Log.e(TAG, "распознавание кадра не удалось", error)
                emptyList()
            }
        }
    }

    private fun engineOrNull(): OcrEngine? {
        if (closed) return null
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
        return created
    }

    /**
     * Отпускает нативную сессию — но не здесь и не сейчас.
     *
     * Зовут отсюда с главного потока, когда экран съёмки уходит, и в этот миг
     * на потоке распознавания почти наверняка досчитывается последний кадр:
     * инференс идёт сотни миллисекунд и отмену корутины не замечает — он
     * не в Kotlin, а внутри модели. Удалить сессию прямо сейчас значит выдернуть
     * её из-под работающего вычисления, и приложение падает по SIGSEGV уже
     * в нативном коде — то самое «закрыл сканер и вылетело».
     *
     * Поэтому освобождение встаёт в ту же однопоточную очередь, что и кадры:
     * оно случится сразу за текущим кадром и заведомо до любого следующего.
     * Главный поток при этом не ждёт — иначе закрытие экрана замирало бы
     * на полсекунды. Замок тут на случай, если очередь окажется не одна.
     */
    override fun close() {
        if (closed) return
        closed = true
        closer.launch {
            synchronized(nativeLock) {
                engine?.close()
                engine = null
            }
        }
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
