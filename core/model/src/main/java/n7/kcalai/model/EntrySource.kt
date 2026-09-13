package n7.kcalai.model

/** Чем именно была добавлена запись — нужно для диагностики качества распознавания. */
enum class EntrySource {
    BARCODE,
    TEXT,
    MANUAL,
    LLM,
    VISION,
}
