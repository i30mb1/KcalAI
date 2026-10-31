package n7.kcalai.model

/** Журнал платформы: logcat на Android. Общему коду `android.util.Log` недоступен. */
expect object Log {
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)
}
