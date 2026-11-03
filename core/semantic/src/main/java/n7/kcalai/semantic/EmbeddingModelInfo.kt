package n7.kcalai.semantic

/**
 * Что за отпечатки считает телефон — из `tools/embeddb/model.properties`.
 *
 * [DIM] — длина, на которой сравниваются отпечатки: та же, что у справочника в seed.db.
 * [ID] — ключ кэша личных отпечатков: сменишь модель или длину, и старые перестанут
 * считаться годными сами, без миграции базы.
 */
object EmbeddingModelInfo {
    const val DIM: Int = BuildConfig.MODEL_DIM
    const val ID: String = BuildConfig.MODEL_SHA256 + "/" + BuildConfig.MODEL_DIM
}
