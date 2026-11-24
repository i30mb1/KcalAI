package n7.kcalai.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.coroutines.MainScope
import n7.kcalai.ui.KcalTheme

/**
 * Страница дневника в браузере. Её отдаёт телефон по домашней сети, с ним она
 * и разговаривает — см. [PhoneLink].
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val link = PhoneLink(MainScope()).also { it.start() }
    ComposeViewport {
        KcalTheme {
            DiaryPage(link)
        }
    }
}
