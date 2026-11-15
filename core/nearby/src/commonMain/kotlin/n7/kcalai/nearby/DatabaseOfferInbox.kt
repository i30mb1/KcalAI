package n7.kcalai.nearby

import n7.kcalai.database.PeerOfferDao
import n7.kcalai.database.PeerOfferEntity

/**
 * Пришедшее от соседа — в базу, откуда его возьмёт лента.
 *
 * Через базу, а не прямым вызовом экрана: сосед присылает, когда присылает,
 * и пережить закрытие экрана предложение должно так же, как сообщения
 * персонажей. Лента подхватит его сама — она и так строится из базы.
 */
class DatabaseOfferInbox(private val dao: PeerOfferDao) : OfferInbox {

    override suspend fun waiting(): Int = dao.waitingCount()

    override suspend fun receive(offer: PeerOfferEntity) {
        dao.insert(offer)
    }
}
