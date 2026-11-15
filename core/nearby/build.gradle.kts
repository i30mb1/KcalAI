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
            // Предложение кладётся в ту же базу, что и дневник.
            api(project(":core:database"))
            // JSON через JsonElement API, как в `:core:remote` и на сервере:
            // компиляторный плагин не нужен.
            implementation(libs.kotlinx.serialization.json)
            // Приёмник — на самом телефоне: сосед шлёт ему напрямую, минуя наши серверы.
            // CIO и на Kotlin/Native собирается, так что приёмник общий.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
