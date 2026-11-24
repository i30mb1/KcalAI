package n7.kcalai.webhost

import kotlinx.coroutines.CancellationException
import n7.kcalai.webhost.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Страница дневника из ресурсов модуля: её кладёт туда сборка `:feature:web`.
 *
 * Compose Resources, а не assets и бандл по отдельности: один путь на обе платформы,
 * и читает его общий код.
 */
object ResourceWebBundle : WebBundle {

    @OptIn(ExperimentalResourceApi::class)
    override suspend fun read(path: String): ByteArray? = try {
        Res.readBytes("files/web/$path")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (missing: Exception) {
        // MissingResourceException на каждой платформе своя, а исход один: такого файла нет.
        null
    }
}
