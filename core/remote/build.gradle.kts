plugins {
    id("n7.kcalai.kmp-library")
}

kotlin {
    android {
        namespace = "n7.kcalai.remote"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:repositories"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            // JSON через JsonElement API, как на сервере: компиляторный плагин не нужен.
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
