package n7.kcalai.model

actual object Log {
    actual fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
    }

    actual fun w(tag: String, message: String, error: Throwable?) {
        android.util.Log.w(tag, message, error)
    }

    actual fun e(tag: String, message: String, error: Throwable?) {
        android.util.Log.e(tag, message, error)
    }
}
