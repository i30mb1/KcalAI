package n7.kcalai.database

import androidx.room.Entity

/**
 * Как текст размечен для EmbeddingGemma.
 *
 * Запрос и документ модель видит по-разному, и отпечатки одной и той же строки
 * в двух разметках — разные векторы. Поэтому разметка — часть ключа кэша.
 */
enum class EmbeddingMarkup { QUERY, DOCUMENT }

/**
 * Отпечаток текста, посчитанный на телефоне: свой продукт или прошлый запрос.
 *
 * Справочнику отпечатки считает сборка, а личным данным — некому, кроме телефона:
 * ~70 мс на строку. Хранятся, чтобы не пересчитывать сотни строк на каждом запуске.
 *
 * [model] — какой моделью и до какой длины посчитано. Сменилась модель — старые
 * отпечатки с новыми несравнимы, и кэш выбрасывает их целиком.
 *
 * Не `data class`: у массива нет содержательного `equals`, а сравнивать строки
 * кэша незачем.
 */
@Entity(tableName = "text_embedding", primaryKeys = ["markup", "text"])
class TextEmbeddingEntity(
    val markup: EmbeddingMarkup,
    val text: String,
    val model: String,
    /** float32 little-endian, как `generic_embedding.vec` справочника. */
    val vec: ByteArray,
)

/** Сколько раз по одному и тому же запросу выбирали один и тот же продукт. */
data class PastPickRow(
    val query: String,
    val pickedRef: String,
    val times: Int,
    val lastAt: Long,
)
