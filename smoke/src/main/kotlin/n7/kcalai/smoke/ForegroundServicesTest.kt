package n7.kcalai.smoke

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.zip.ZipFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Foreground-сервиса в сборке для людей нет, раз нет разрешения на него.
 *
 * Разрешения вырезаны в манифесте приложения: Google Play требует за них декларацию с видео.
 * Но зависимости приносят и сами сервисы. Распаковка Play Asset Delivery включает свой сервис
 * при старте и зовёт startForeground без try/catch: на Android 14+ без разрешения это
 * SecurityException в главном потоке у человека, поставившего приложение из Play. Локальный
 * bundletool этот путь не проходит, так что увидеть его можно только здесь, ещё до загрузки.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServicesTest {

    @Test
    @SdkSuppress(minSdkVersion = FIRST_WITH_SERVICE_TYPE)
    fun noForegroundServiceWithoutPermission() {
        val packages = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val app = packages.getPackageInfo(
            APP,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS,
        )

        val foreground = app.services.orEmpty().filter { it.foregroundServiceType != 0 }.map { it.name }
        val permitted = PERMISSION in app.requestedPermissions.orEmpty()

        assertTrue("foreground-сервисы без разрешения: $foreground", permitted || foreground.isEmpty())
    }

    @Test
    fun nothingCallsStartForeground() {
        val packages = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val apk = packages.getApplicationInfo(APP, 0).sourceDir

        val calledByName = ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".dex") }
                .any { entry -> zip.getInputStream(entry).use { it.readBytes() }.contains(METHOD_NAME) }
        }

        assertFalse("в коде остался вызов startForeground", calledByName)
    }

    /** Подстрока подряд. Простой перебор: файл один и прогон один. */
    private fun ByteArray.contains(part: ByteArray): Boolean =
        (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    private companion object {
        const val APP = "n7.kcalai.minified"
        const val PERMISSION = "android.permission.FOREGROUND_SERVICE"
        const val FIRST_WITH_SERVICE_TYPE = 29

        /**
         * Строка в таблице DEX: длина, символы, ноль. С ними startForeground не спутать
         * со startForegroundService, который и правда есть в ContextCompat.
         */
        val METHOD_NAME = byteArrayOf(15) + "startForeground".toByteArray() + 0
    }
}
