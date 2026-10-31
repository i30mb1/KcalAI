package n7.kcalai.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.asByteWriteChannel
import io.ktor.utils.io.copyAndClose
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import n7.kcalai.model.Log

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
 * Минимальный HTTP поверх Ktor.
 *
 * Движок у каждой платформы свой и подбирается по зависимостям: на Android —
 * поверх `HttpURLConnection`, без OkHttp (~800 КБ) ради трёх запросов.
 *
 * Ни одна функция здесь не бросает на сетевых сбоях: для вызывающей стороны
 * «сети нет» и «товара нет» — один и тот же исход, ведущий в форму ручного ввода.
 * Отмена корутины из этого правила исключена и пробрасывается как есть.
 */
internal object Http {

    private const val TAG = "KcalHttp"
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val READ_TIMEOUT_MS = 15_000L

    /** Человек ждёт ответа стоя у полки, и «телефон завис» здесь хуже, чем «не нашли». */
    private const val MAX_BODY_BYTES = 512L * 1024

    /** Закачка справочника и отправка кадров идут в фоне — там ждать можно. */
    private const val LONG_READ_TIMEOUT_MS = 60_000L

    private val client = HttpClient {
        // Коды ответа разбирают вызывающие: 404 здесь — штатный ответ, а не исключение.
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = READ_TIMEOUT_MS
        }
    }

    suspend fun get(url: String, userAgent: String): HttpResponse? =
        request(url, userAgent, method = HttpMethod.Get, payload = null)

    suspend fun postJson(url: String, userAgent: String, payload: String): HttpResponse? =
        request(url, userAgent, method = HttpMethod.Post, payload = payload)

    /** Файл целиком на диск, потоком: справочник — мегабайты, в память его не читаем. */
    suspend fun download(url: String, userAgent: String, to: String): Boolean {
        val path = Path(to)
        return try {
            client.prepareGet(url) {
                header(HttpHeaders.UserAgent, userAgent)
                timeout { socketTimeoutMillis = LONG_READ_TIMEOUT_MS }
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute false
                response.bodyAsChannel().copyAndClose(SystemFileSystem.sink(path).asByteWriteChannel())
                true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.i(TAG, "GET $url (файл) не удался: ${error.message}")
            SystemFileSystem.delete(path, mustExist = false)
            false
        }
    }

    /** @param files тройки (имя поля, имя файла, байты) */
    suspend fun postMultipart(
        url: String,
        userAgent: String,
        headers: Map<String, String>,
        fields: Map<String, String>,
        files: List<Triple<String, String, ByteArray>>,
    ): HttpResponse? = try {
        val response = client.submitFormWithBinaryData(
            url = url,
            formData = formData {
                fields.forEach { (name, value) -> append(name, value) }
                files.forEach { (field, fileName, bytes) ->
                    append(
                        field,
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        },
                    )
                }
            },
        ) {
            header(HttpHeaders.UserAgent, userAgent)
            headers.forEach { (name, value) -> header(name, value) }
            timeout { socketTimeoutMillis = LONG_READ_TIMEOUT_MS }
        }
        HttpResponse(response.status.value, response.bodyAsText())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.i(TAG, "POST $url (multipart) не удался: ${error.message}")
        null
    }

    private suspend fun request(
        url: String,
        userAgent: String,
        method: HttpMethod,
        payload: String?,
    ): HttpResponse? = try {
        val response = client.request(url) {
            this.method = method
            // Open Food Facts режет запросы без описательного User-Agent,
            // и это их прямое требование, а не рекомендация.
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            if (payload != null) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }
        val body = response.bodyAsChannel().readRemaining(MAX_BODY_BYTES).readString()
        HttpResponse(response.status.value, body)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        // Сюда же попадает SecurityException без разрешения INTERNET — чинится
        // в манифесте, а не ретраями, но и ронять поиск из-за него незачем.
        Log.i(TAG, "${method.value} $url не удался: ${error.message}")
        null
    }
}
