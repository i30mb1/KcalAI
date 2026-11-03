import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.asset.pack)
}

/**
 * Модель поиска по смыслу для телефона, 165 МБ.
 *
 * В базовый модуль она не влезает (AAB упёрся бы в лимит Play в 200 МБ), поэтому
 * едет отдельным паком: fast-follow Play докачивает сам сразу после установки, а пока
 * пака нет, приложение ищет по словам, как раньше. В APK из Android Studio паков нет
 * вовсе — на эмулятор модель кладётся руками, см. EmbeddingModel.
 */
assetPack {
    packName.set("embedding")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}

val model = Properties().apply { rootProject.file("tools/embeddb/model.properties").reader().use(::load) }

/** Файл модели в git не хранится: сборка берёт его из tools/embeddb/model или с Hugging Face. */
abstract class FetchModel : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:InputFiles @get:Optional abstract val cached: RegularFileProperty
    @get:OutputFile abstract val target: RegularFileProperty

    @TaskAction
    fun fetch() {
        val out = target.get().asFile
        val local = cached.orNull?.asFile
        if (local != null && local.exists() && sha(local) == sha256.get()) {
            local.copyTo(out, overwrite = true)
            return
        }
        val part = File(out.path + ".part")
        URI(url.get()).toURL().openStream().use { input -> part.outputStream().use(input::copyTo) }
        check(sha(part) == sha256.get()) { "sha256 модели не совпал с tools/embeddb/model.properties" }
        part.renameTo(out)
    }

    private fun sha(file: File): String {
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
}

val fetchModel by tasks.registering(FetchModel::class) {
    url.set(model.getProperty("url"))
    sha256.set(model.getProperty("sha256"))
    cached.set(rootProject.layout.projectDirectory.file("tools/embeddb/model/${model.getProperty("file")}"))
    target.set(layout.projectDirectory.file("src/main/assets/${model.getProperty("file")}"))
}

// Пак собирается только в AAB, и манифест пака — первое, что для этого строится.
tasks.named("generateAssetPackManifest") { dependsOn(fetchModel) }
