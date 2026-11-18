package n7.kcalai.feature.scanner

import n7.kcalai.model.TextLine
import n7.kcalai.repositories.LabelParser
import n7.kcalai.repositories.LabelReading

/**
 * Путь кадра этикетки — общий для Android и iOS.
 *
 * Платформа только достаёт из кадра яркость (для резкости) и строки текста:
 * свой движок распознавания на Android, Vision на iOS. Что делать с кадром дальше,
 * решается здесь, и на обеих платформах одинаково:
 *
 * - мыльный ([isSharp] — нет) не идёт ни в распознавание, ни в голосование:
 *   распознаватель по нему отдаёт не пустоту, а кашу, и каша голосует наравне
 *   с честным чтением. Наружу уходит прошлый резкий кадр с пометкой — карточка
 *   считает, сколько камера мылит подряд, и через несколько секунд просит вписать руками;
 * - нет распознавания на этом устройстве — это состояние, о нём говорят один раз;
 * - прочитанные строки разбираются и голосуют вместе с прошлыми кадрами.
 *
 * Не потокобезопасен: кадры у обеих платформ идут по одному, следующий ждёт прошлый.
 */
internal class LabelPipeline {

    /** Кадр для карточки и голоса, из которых он вышел, — для лога и записи кадров. */
    class Read(val frame: LabelFrame, val verdict: LabelConsensus.Verdict)

    private val consensus = LabelConsensus()
    private var lastFrame: LabelFrame? = null
    private var reportedUnavailable = false

    fun isSharp(sharpness: Double): Boolean = sharpness >= FrameSharpness.MIN

    /** Мыльный кадр: прошлый резкий — голоса и поля не дёргаются, — с пометкой. */
    fun blurred(elapsedMs: Long): LabelFrame =
        (lastFrame ?: LabelFrame(LabelReading.EMPTY, 0)).copy(elapsedMs = elapsedMs, sharp = false)

    /** Распознавания нет: кадр с этим один раз, дальше `null` — сказано уже. */
    fun unavailable(): LabelFrame? {
        if (reportedUnavailable) return null
        reportedUnavailable = true
        return LabelFrame(LabelReading.EMPTY, 0, available = false)
    }

    /** Строки резкого кадра -> разбор, голосование и кадр для карточки. */
    fun read(lines: List<TextLine>, elapsedMs: Long, size: String): Read {
        val verdict = consensus.add(LabelParser.parseLines(lines))
        val frame = LabelFrame(
            reading = verdict.reading,
            elapsedMs = elapsedMs,
            size = size,
            fields = verdict.fields,
            frames = verdict.frames,
            names = verdict.names,
            name = verdict.name,
        )
        lastFrame = frame
        return Read(frame, verdict)
    }

    companion object {
        /**
         * Сколько держать паузу после мыльного кадра — примерно кадр распознавания.
         * Без неё пропуск отпускал кадр за миллисекунды, и шли все тридцать в секунду.
         */
        const val SKIP_PAUSE_MS = 300L
    }
}
