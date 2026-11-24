package n7.kcalai.webhost

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Чем назвался браузер — это подпись в вопросе Яйца «Пустить?». Человеку нужно
 * узнать в ней свой ноутбук, а не прочитать строку User-Agent.
 */
class BrowserLabelTest {

    @Test
    fun `браузер и система — словами`() {
        assertEquals(
            "Chrome на Windows",
            browserLabel("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"),
        )
        assertEquals(
            "Edge на Windows",
            browserLabel("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0"),
        )
        assertEquals(
            "Safari на Mac",
            browserLabel("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/19.0 Safari/605.1.15"),
        )
        assertEquals(
            "Firefox на Linux",
            browserLabel("Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"),
        )
        assertEquals(
            "Chrome на Android",
            browserLabel("Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"),
        )
        assertEquals(
            "Safari на iPhone",
            browserLabel("Mozilla/5.0 (iPhone; CPU iPhone OS 19_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/19.0 Mobile/15E148 Safari/604.1"),
        )
    }

    @Test
    fun `незнакомое — просто браузер`() {
        assertEquals("Браузер", browserLabel(null))
        assertEquals("Браузер", browserLabel("curl/8.5.0"))
    }
}
