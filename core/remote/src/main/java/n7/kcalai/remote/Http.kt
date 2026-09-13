package n7.kcalai.remote

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ответ сервера ровно в том объёме, который нам нужен.
 *
 * Тело приходит и с ошибочными кодами: сервер вправе объяснить, что именно не так,
 * и выбрасывать это объяснение до разбора кода неправильно.
 */
internal class HttpResponse(val code: Int, val body: String) {
    val isSuccess: Boolean get() = code in 200..299
}

/**
 * Минимальный HTTP поверх того, что уже лежит в Android.
 *
 * Запросов в приложении три, и ради них не стоит ни OkHttp (~800 КБ), ни
 * kotlinx-serialization (компиляторный плагин, которых проект избегает).
 * `HttpURLConnection` умеет пул соединений, gzip и TLS сам.
 *
 * Ни одна функция здесь не бросает на сетевых сбоях: для вызывающей стороны
 * «сети нет» и «товара нет» — один и тот же исход, ведущий в форму ручного ввода.
 * Отмена корутины из этого правила исключена и пробрасывается как есть.
 */
internal object Http {

    private const val TAG = "KcalHttp"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Человек ждёт ответа стоя у полки, и «телефон завис» здесь хуже, чем «не нашли». */
    private const val MAX_BODY_BYTES = 512 * 1024

    suspend fun get(url: String, userAgent: String): HttpResponse? =
        request(url, userAgent, method = "GET", payload = null)

    suspend fun postJson(url: String, userAgent: String, payload: String): HttpResponse? =
        request(url, userAgent, method = "POST", payload = payload)

    private suspend fun request(
        url: String,
        userAgent: String,
        method: String,
        payload: String?,
    ): HttpResponse? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // Open Food Facts режет запросы без описательного User-Agent,
                // и это их прямое требование, а не рекомендация.
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }

            payload?.let { body ->
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buffer = CharArray(MAX_BODY_BYTES)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }.orEmpty()

            HttpResponse(code, body)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            Log.i(TAG, "$method $url не удался: ${error.message}")
            null
        } catch (error: SecurityException) {
            // Разрешения INTERNET нет — чинится в манифесте, а не ретраями.
            Log.e(TAG, "$method $url запрещён", error)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
