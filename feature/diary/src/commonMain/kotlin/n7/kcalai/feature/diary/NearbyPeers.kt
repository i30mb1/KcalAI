package n7.kcalai.feature.diary

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import n7.kcalai.model.Nutriments

/**
 * Человек рядом глазами экрана.
 *
 * Имя объявлено его телефоном и ничем не подтверждено — подпись, а не удостоверение.
 *
 * @param id чем его звать при отправке; экран про адрес и порт не знает
 */
data class NearbyPerson(val id: String, val name: String)

/**
 * Телефоны рядом — для экрана.
 *
 * Интерфейс здесь, а реализация в платформенном модуле: mDNS и сокеты — такая же
 * деталь сборки приложения, как WorkManager, и в модуле экрана им делать нечего.
 * `null` вместо реализации — фича выключена, соседей не бывает. Образец — [DiaryOutbox].
 */
interface NearbyPeers {

    /** Кто рядом. Поиск идёт, пока на поток подписаны: в кармане искать некого. */
    fun nearby(): Flow<List<NearbyPerson>>

    /**
     * Адрес страницы дневника для браузера в той же сети — на том же сервере, что
     * принимает соседей. `null` — сервер не поднят или телефон не в Wi-Fi.
     */
    fun address(): Flow<String?> = flowOf(null)

    /** @return приняли ли. `false` — не ушло: сосед молчит или отказал. */
    suspend fun send(id: String, food: String, grams: Int, nutriments: Nutriments): Boolean
}

/**
 * Зёрнышко зовёт записать соседу.
 *
 * Функцией, как [outboxOffer] и [peerOffer]: что сказано, проверяется тестом.
 * Зёрнышко — потому что рядом кто-то свой, а это его забота.
 */
fun nearbyOffer(names: List<String>): String = when (names.size) {
    0 -> ""
    // Без «ей» и «ему»: род по имени не угадывается, а приложение говорит
    // о человеке в роде только там, где пол известен.
    1 -> "Рядом ${names.single()}. Отправить еду?"
    else -> "Рядом свои: ${names.joinToString(", ")}. Кому отправить?"
}
