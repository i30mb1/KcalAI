package n7.kcalai.model

/** Модель, которую приложение докачивает в фоне. */
enum class BackgroundModel {
    /** Поиск по смыслу: пак Play. */
    SEARCH,

    /** Gemini Nano: качает система. */
    ASSISTANT,
}

/** Докуда дошла докачка. Человек этого не видит, и рассказывают ему персонажи. */
enum class ModelDelivery {
    DOWNLOADING,

    /** Модель большая: по мобильной сети Play её сам не качает. */
    WAITING_FOR_WIFI,

    FAILED,
    READY,
}
