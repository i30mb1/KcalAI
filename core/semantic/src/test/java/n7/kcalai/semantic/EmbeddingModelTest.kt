package n7.kcalai.semantic

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import n7.kcalai.model.ModelDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddingModelTest {

    /** Человеку всё равно, ждёт ли Play очереди или уже перекладывает файл: модель в пути. */
    @Test
    fun `всё что идёт своим ходом — докачка`() {
        assertEquals(ModelDelivery.DOWNLOADING, assetPackDelivery(AssetPackStatus.PENDING))
        assertEquals(ModelDelivery.DOWNLOADING, assetPackDelivery(AssetPackStatus.DOWNLOADING))
        assertEquals(ModelDelivery.DOWNLOADING, assetPackDelivery(AssetPackStatus.TRANSFERRING))
    }

    @Test
    fun `пак на месте — модель докачана`() {
        assertEquals(ModelDelivery.READY, assetPackDelivery(AssetPackStatus.COMPLETED))
    }

    /** Большой пак Play сам по мобильной сети не качает: ждёт Wi-Fi или согласия. */
    @Test
    fun `большой пак ждёт Wi-Fi`() {
        assertEquals(ModelDelivery.WAITING_FOR_WIFI, assetPackDelivery(AssetPackStatus.WAITING_FOR_WIFI))
        assertEquals(ModelDelivery.WAITING_FOR_WIFI, assetPackDelivery(AssetPackStatus.REQUIRES_USER_CONFIRMATION))
    }

    @Test
    fun `сбой и отмена — не докачалось`() {
        assertEquals(ModelDelivery.FAILED, assetPackDelivery(AssetPackStatus.FAILED))
        assertEquals(ModelDelivery.FAILED, assetPackDelivery(AssetPackStatus.CANCELED))
    }

    /** Пака нет, и рассказывать не о чем: APK из Android Studio или Play ещё не начинал. */
    @Test
    fun `пак не начат — говорить не о чем`() {
        assertNull(assetPackDelivery(AssetPackStatus.NOT_INSTALLED))
        assertNull(assetPackDelivery(AssetPackStatus.UNKNOWN))
    }
}
