package n7.kcalai.embeddb

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Properties
import kotlin.math.sqrt

/**
 * Отпечатки продуктов справочника для поиска по смыслу — таблица `generic_embedding` в seed.db.
 *
 *     ./gradlew -q run --args="<путь к seed.db>"
 *
 * Зовёт его `tools/builddb/build_seed.py`; руками запускать незачем. Модель (165 МБ)
 * скачивается в `model/` при первом запуске и сверяется с sha256 из `model.properties`.
 */
fun main(args: Array<String>) {
    val seed = File(args.single())
    val tool = File(System.getProperty("user.dir"))
    val props = Properties().apply { File(tool, "model.properties").reader(Charsets.UTF_8).use(::load) }
    val model = ensureModel(File(tool, "model/${props.getProperty("file")}"), props)
    val dim = props.getProperty("dim").toInt()

    DriverManager.getConnection("jdbc:sqlite:${seed.absolutePath}").use { db ->
        val foods = db.createStatement().use { st ->
            val rs = st.executeQuery(
                "SELECT g.id, g.name_ru, group_concat(a.alias, '|') FROM generic g " +
                    "LEFT JOIN generic_alias a ON a.generic_id = g.id AND a.lang = 'ru' " +
                    "GROUP BY g.id ORDER BY g.id"
            )
            buildList { while (rs.next()) add(Triple(rs.getLong(1), rs.getString(2), rs.getString(3).orEmpty())) }
        }

        EmbeddingEngine(EmbeddingEngineConfig(modelPath = model.path, backend = Backend.CPU())).use { engine ->
            engine.initialize()
            val options = EmbeddingOptions(normalize = true)
            db.autoCommit = false
            db.createStatement().use { st ->
                st.executeUpdate("DROP TABLE IF EXISTS generic_embedding")
                st.executeUpdate(
                    "CREATE TABLE generic_embedding (" +
                        "generic_id INTEGER PRIMARY KEY REFERENCES generic(id), vec BLOB NOT NULL)"
                )
            }
            db.prepareStatement("INSERT INTO generic_embedding (generic_id, vec) VALUES (?, ?)").use { insert ->
                for ((id, name, aliases) in foods) {
                    val full = engine.computeEmbedding(listOf(InputData.Text(document(name, aliases))), options).embedding
                    insert.setLong(1, id)
                    insert.setBytes(2, encode(truncate(full, dim)))
                    insert.executeUpdate()
                }
            }
            db.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)").use { meta ->
                for ((key, value) in listOf("embedding_model" to props.getProperty("sha256"), "embedding_dim" to "$dim")) {
                    meta.setString(1, key)
                    meta.setString(2, value)
                    meta.executeUpdate()
                }
            }
            db.commit()
        }
        println("ОК: ${foods.size} отпечатков по $dim чисел")
    }
}

/**
 * Что модель видит о продукте: название и до шести русских алиасов.
 *
 * С алиасами точнее, чем по одному названию (40 из 45 против 35 на тестовых фразах):
 * «гречневая каша» и «яичница» лежат в алиасах, а не в названии. Формат «title | text» —
 * разметка документа для EmbeddingGemma; запрос на телефоне размечается как query.
 */
private fun document(name: String, aliases: String): String {
    val extra = aliases.split('|').filter { it.isNotBlank() && it != name.lowercase() }.distinct().take(6)
    val text = if (extra.isEmpty()) name else "$name (${extra.joinToString(", ")})"
    return "title: none | text: $text"
}

/** Начало вектора EmbeddingGemma — сам по себе отпечаток, но длину 1 надо вернуть. */
private fun truncate(v: FloatArray, dim: Int): FloatArray {
    val norm = sqrt((0 until dim).sumOf { (v[it] * v[it]).toDouble() }).toFloat()
    return FloatArray(dim) { v[it] / norm }
}

/** float32 little-endian — так читает `decodeEmbedding` в :core:fooddb. */
private fun encode(v: FloatArray): ByteArray =
    ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        .apply { v.forEach(::putFloat) }.array()

private fun ensureModel(file: File, props: Properties): File {
    val sha = props.getProperty("sha256")
    if (file.exists() && sha256(file) == sha) return file
    file.parentFile.mkdirs()
    println("Качаю ${props.getProperty("file")}…")
    val tmp = File(file.path + ".part")
    URI(props.getProperty("url")).toURL().openStream().use { input -> tmp.outputStream().use(input::copyTo) }
    check(sha256(tmp) == sha) { "sha256 модели не совпал — файл на Hugging Face подменили или закачка битая" }
    tmp.renameTo(file)
    return file
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
