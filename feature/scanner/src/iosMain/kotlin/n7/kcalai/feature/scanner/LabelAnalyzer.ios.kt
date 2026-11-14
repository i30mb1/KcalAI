package n7.kcalai.feature.scanner

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import n7.kcalai.model.Log
import n7.kcalai.repositories.LabelParser
import n7.kcalai.repositories.LabelReading
import platform.CoreVideo.CVPixelBufferRef
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * Кадр камеры -> разобранная таблица пищевой ценности.
 *
 * Своего движка на iOS не нужно: Vision знает кириллицу, и подписи «Белки»
 * и «Жиры» связываются со своими числами прямо. Дальше — общий путь, тот же,
 * что на Android: [LabelParser] разбирает строки, [LabelConsensus] набирает
 * голоса по кадрам.
 *
 * Пока считается один кадр, остальные отбрасываются сразу. Копить их нельзя:
 * распознавание идёт десятки миллисекунд, кадры приходят каждые тридцать, и
 * очередь росла бы быстрее, чем расходится.
 */
@OptIn(ExperimentalForeignApi::class)
internal class LabelAnalyzer(
    private val scope: CoroutineScope,
    /**
     * Читать этикетку. `false` — кадр ищет только штрих-код: шаг кода в том же
     * окне. Спрашивается на каждом кадре — окно переходит к карточке посреди
     * сессии, а камера и анализ при этом не пересоздаются.
     */
    private val reading: () -> Boolean,
    private val onFrame: (LabelFrame) -> Unit,
) {
    private val busy = atomic(false)
    private val consensus = LabelConsensus()

    fun analyze(frame: CVPixelBufferRef, width: Int, height: Int) {
        if (!reading()) return
        if (!busy.compareAndSet(expect = false, update = true)) return

        scope.launch {
            val started = NSDate().timeIntervalSince1970
            try {
                val lines = recognizeLabel(frame, width, height)
                if (lines == null) {
                    onFrame(LabelFrame(LabelReading.EMPTY, 0, available = false))
                    return@launch
                }

                val verdict = consensus.add(LabelParser.parseLines(lines))
                val elapsed = ((NSDate().timeIntervalSince1970 - started) * MILLIS_PER_SECOND).toLong()
                onFrame(
                    LabelFrame(
                        reading = verdict.reading,
                        elapsedMs = elapsed,
                        size = "$width×$height",
                        fields = verdict.fields,
                        frames = verdict.frames,
                        names = verdict.names,
                        name = verdict.name,
                    ),
                )
            } catch (error: Exception) {
                // Один плохой кадр — не повод закрывать камеру: следующий придёт через тридцать миллисекунд.
                Log.w(TAG, "кадр не разобран", error)
            } finally {
                busy.value = false
            }
        }
    }

    private companion object {
        const val TAG = "LabelAnalyzer"
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
