package n7.kcalai.webhost

/**
 * User-Agent -> «Chrome на Windows»: подпись в вопросе Яйца «Пустить?».
 *
 * Человеку надо узнать в ней свой ноутбук, а не прочитать строку User-Agent.
 * Подпись, а не удостоверение: браузер назвать себя может как угодно, и пускает
 * не подпись, а код, совпавший с тем, что на экране ноутбука.
 *
 * Порядок проверок важен: Edge и Chrome пишут о себе и «Chrome», и «Safari»,
 * а Android — и «Linux».
 */
fun browserLabel(userAgent: String?): String {
    val agent = userAgent.orEmpty()
    val browser = BROWSERS.firstOrNull { (marker, _) -> marker in agent }?.second ?: return UNKNOWN
    val system = SYSTEMS.firstOrNull { (marker, _) -> marker in agent }?.second ?: return browser
    return "$browser на $system"
}

private const val UNKNOWN = "Браузер"

private val BROWSERS = listOf(
    "Edg/" to "Edge",
    "OPR/" to "Opera",
    "YaBrowser/" to "Яндекс Браузер",
    "Firefox/" to "Firefox",
    "Chrome/" to "Chrome",
    "Safari/" to "Safari",
)

private val SYSTEMS = listOf(
    "iPhone" to "iPhone",
    "iPad" to "iPad",
    "Android" to "Android",
    "Windows" to "Windows",
    "Macintosh" to "Mac",
    "Linux" to "Linux",
)
