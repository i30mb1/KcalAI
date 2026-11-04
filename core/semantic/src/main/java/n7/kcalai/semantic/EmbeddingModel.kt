package n7.kcalai.semantic

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import n7.kcalai.model.ModelDelivery

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

    /** Файл модели или `null`, если её здесь нет — пока или совсем. */
    fun locate(): File? {
        if (!supportedAbi()) return null
        File(context.filesDir, "models/${BuildConfig.MODEL_FILE}").takeIf { it.isFile }?.let { return it }

        val location = AssetPackManagerFactory.getInstance(context).getPackLocation(PACK) ?: return null
        return location.assetsPath()?.let { File(it, BuildConfig.MODEL_FILE) }?.takeIf { it.isFile }
    }

    /**
     * Как идёт доставка модели. Подписка заодно просит пак: fast-follow Play качает сам,
     * но после отмены или без сети — только если попросить, и просить надо на старте,
     * а не на первом поиске, который иначе уходил впустую.
     *
     * Без Play (APK из Android Studio) просьба тихо проваливается, это не ошибка:
     * поток просто молчит. На телефоне, где движок не загрузится, — тоже.
     */
    fun delivery(): Flow<ModelDelivery> = callbackFlow {
        if (!supportedAbi()) {
            close()
            return@callbackFlow
        }
        if (locate() != null) {
            trySend(ModelDelivery.READY)
            close()
            return@callbackFlow
        }

        val packs = AssetPackManagerFactory.getInstance(context)
        val listener = AssetPackStateUpdateListener { state ->
            if (state.name() == PACK) assetPackDelivery(state.status())?.let { trySend(it) }
        }
        packs.registerListener(listener)
        packs.getPackStates(listOf(PACK)).addOnSuccessListener { states ->
            states.packStates()[PACK]?.let { assetPackDelivery(it.status()) }?.let { trySend(it) }
        }
        packs.fetch(listOf(PACK)).addOnFailureListener { Log.i(TAG, "пак $PACK недоступен: $it") }
        awaitClose { packs.unregisterListener(listener) }
    }.distinctUntilChanged()

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

/**
 * Статус пака словами, которые понятны человеку. `null` — говорить не о чем: пака нет
 * (APK из Android Studio) или Play к нему ещё не приступил.
 */
internal fun assetPackDelivery(status: Int): ModelDelivery? = when (status) {
    AssetPackStatus.PENDING, AssetPackStatus.DOWNLOADING, AssetPackStatus.TRANSFERRING -> ModelDelivery.DOWNLOADING
    AssetPackStatus.WAITING_FOR_WIFI, AssetPackStatus.REQUIRES_USER_CONFIRMATION -> ModelDelivery.WAITING_FOR_WIFI
    AssetPackStatus.COMPLETED -> ModelDelivery.READY
    AssetPackStatus.FAILED, AssetPackStatus.CANCELED -> ModelDelivery.FAILED
    else -> null
}
