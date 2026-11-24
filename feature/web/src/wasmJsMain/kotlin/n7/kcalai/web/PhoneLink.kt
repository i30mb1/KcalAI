package n7.kcalai.web

import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import n7.kcalai.webapi.PageState
import n7.kcalai.webapi.ToPage
import n7.kcalai.webapi.ToPhone
import n7.kcalai.webapi.WEB_SOCKET_PATH
import n7.kcalai.webapi.after
import n7.kcalai.webapi.lost
import n7.kcalai.webapi.encode
import n7.kcalai.webapi.parseToPage
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket

/**
 * Связь страницы с телефоном, который её отдал.
 *
 * Телефон держит страницу, пока на нём открыт дневник: погас экран — связь рвётся.
 * Поэтому оборванная связь — обычное дело, и страница стучится снова сама, а человеку
 * говорит, что сделать (см. [PageState.Lost]).
 *
 * Чем браузер назвался, хранится в `localStorage`: пущенного раз браузера телефон
 * узнаёт и больше не спрашивает.
 */
class PhoneLink(private val scope: CoroutineScope) {

    private val current = MutableStateFlow<PageState>(PageState.Connecting)

    val state: StateFlow<PageState> = current.asStateFlow()

    private var socket: WebSocket? = null

    private var left = false

    fun start() = connect()

    fun send(message: ToPhone) {
        socket?.takeIf { it.readyState == WebSocket.OPEN }?.send(encode(message))
    }

    /**
     * «Выйти» — на чужом компьютере: телефон забывает браузер, и страница больше не стучится.
     * Вернуться — обновить страницу и пустить браузер на телефоне снова.
     */
    fun leave() {
        send(ToPhone.Leave)
        localStorage.removeItem(TOKEN_KEY)
        left = true
        current.value = PageState.Left
    }

    /** «Попросить ещё раз»: заново, под новым именем — старое уже не пустили. */
    fun askAgain() {
        localStorage.removeItem(TOKEN_KEY)
        socket?.close()
    }

    private fun connect() {
        val opened = WebSocket("ws://${window.location.host}$WEB_SOCKET_PATH")
        socket = opened
        opened.onopen = { opened.send(encode(ToPhone.Hello(localStorage.getItem(TOKEN_KEY)))) }
        opened.onmessage = { event: MessageEvent ->
            event.data?.toString()?.let(::parseToPage)?.let(::heard)
        }
        opened.onclose = {
            if (socket === opened && !left) {
                socket = null
                // Что было на экране, остаётся: дневник — неживым, отказ — отказом.
                current.update { it.lost() }
                scope.launch {
                    delay(RETRY_MS)
                    connect()
                }
            }
        }
    }

    private fun heard(message: ToPage) {
        if (message is ToPage.Pairing) localStorage.setItem(TOKEN_KEY, message.token)
        current.update { it.after(message) }
    }

    private companion object {
        const val TOKEN_KEY = "kcalai.token"

        /** Телефон вернётся, как только на нём откроют дневник: стучимся часто, но не каждый кадр. */
        const val RETRY_MS = 3_000L
    }
}
