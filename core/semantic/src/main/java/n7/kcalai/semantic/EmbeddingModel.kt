package n7.kcalai.semantic

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Где на телефоне лежит модель поиска по смыслу.
 *
 * Два места. Пак Play `embedding` (fast-follow) — так её получают люди. И
 * `filesDir/models/` — так её кладут на эмулятор и в отладочную сборку: APK из
 * Android Studio паков не содержит вовсе.
 *
 *     adb shell run-as n7.kcalai mkdir -p files/models
 *     adb exec-in run-as n7.kcalai sh -c 'cat > files/models/<файл>' < tools/embeddb/model/<файл>
 */
internal class EmbeddingModel(private val context: Context) {

    private val fetchRequested = AtomicBoolean(false)

    /** Файл модели или `null`, если её здесь нет — пока или совсем. */
    fun locate(): File? {
        if (!supportedAbi()) return null
        File(context.filesDir, "models/${BuildConfig.MODEL_FILE}").takeIf { it.isFile }?.let { return it }

        val packs = AssetPackManagerFactory.getInstance(context)
        val location = packs.getPackLocation(PACK)
        if (location == null) {
            // Fast-follow Play качает сам, но после отмены или без сети — только если попросить.
            // Без Play (APK из Android Studio) просьба тихо проваливается, это не ошибка.
            if (fetchRequested.compareAndSet(false, true)) {
                packs.fetch(listOf(PACK)).addOnFailureListener { Log.i(TAG, "пак $PACK недоступен: $it") }
            }
            return null
        }
        return location.assetsPath()?.let { File(it, BuildConfig.MODEL_FILE) }?.takeIf { it.isFile }
    }

    private companion object {
        const val TAG = "EmbeddingModel"
        const val PACK = "embedding"

        /**
         * В AAR LiteRT-LM нативные библиотеки только для arm64-v8a и x86_64. Приложение
         * ставится и на 32-битные телефоны (их ABI приносят другие библиотеки), и там
         * загрузка движка упала бы с UnsatisfiedLinkError — проверяем заранее.
         */
        fun supportedAbi(): Boolean =
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
    }
}
