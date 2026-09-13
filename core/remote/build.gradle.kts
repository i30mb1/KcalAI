plugins {
    id("n7.kcalai.android-library")
}

android {
    namespace = "n7.kcalai.remote"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:repositories"))
    api(libs.kotlinx.coroutines.core)

    // HTTP-клиента в зависимостях намеренно нет: запросов три, а HttpURLConnection
    // и org.json уже лежат в Android. OkHttp добавил бы ~800 КБ,
    // kotlinx-serialization — компиляторный плагин, которых проект избегает.
}