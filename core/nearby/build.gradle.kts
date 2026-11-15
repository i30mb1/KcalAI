plugins {
    id("n7.kcalai.kmp-library")
}

kotlin {
    android {
        namespace = "n7.kcalai.nearby"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            // Числа с чужого телефона проверяются тем же валидатором, что и свои:
            // доверять соседу не больше, чем своему серверу.
            api(project(":core:repositories"))
            api(libs.kotlinx.coroutines.core)
            // JSON через JsonElement API, как в `:core:remote` и на сервере:
            // компиляторный плагин не нужен.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
