import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/*
 * Страница дневника в браузере: Compose на Kotlin/Wasm.
 *
 * Собранный бандл телефон отдаёт сам, по домашней сети, — своего хостинга у страницы нет.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("n7.kcalai.web-tooling")
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "web.js" }
        }
        binaries.executable()
    }

    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
    }
}
