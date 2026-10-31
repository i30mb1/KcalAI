package n7.kcalai.model

actual object Log {
    actual fun i(tag: String, message: String) = println("I/$tag: $message")

    actual fun w(tag: String, message: String, error: Throwable?) =
        println("W/$tag: $message${error?.let { " — $it" }.orEmpty()}")

    actual fun e(tag: String, message: String, error: Throwable?) =
        println("E/$tag: $message${error?.let { " — $it" }.orEmpty()}")
}
