package n7.kcalai.remote

import n7.kcalai.repositories.RemoteProduct
import n7.kcalai.repositories.RemoteProductSource

/**
 * Источники по порядку: первый ответивший с КБЖУ выигрывает.
 *
 * Ответ с одним названием цепочку не останавливает: следующий источник может знать
 * и числа. Он возвращается, только если чисел не нашлось нигде.
 *
 * Порядок задаётся снаружи и на деле означает «свой сервер, затем Open Food Facts».
 * Свой первым не из вежливости: там лежат российские товары, заведённые людьми
 * вручную, а в OFF их нет и не появится. Заодно ответ оттуда мы вправе
 * распространять дальше, а ответ OFF — нет.
 *
 * Пустой список — рабочее состояние, а не вырожденное: так выглядит приложение
 * без настроенного сервера и с выключенной сетью.
 */
class ChainedProductSource(
    private val sources: List<RemoteProductSource>,
) : RemoteProductSource {

    override suspend fun fetch(gtin: String): RemoteProduct? {
        var named: RemoteProduct? = null
        for (source in sources) {
            val product = source.fetch(gtin) ?: continue
            if (product.nutriments != null) return product
            if (named == null) named = product
        }
        return named
    }
}
