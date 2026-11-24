import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/*
 * Цель wasmJs для общих модулей, которые нужны и странице дневника в браузере.
 *
 * Отдельным плагином, а не в kmp-library: в браузер едет только то, что рисует
 * страница и разбирает протокол. База, справочник и модели остаются на телефоне —
 * им эта цель не нужна, и собирать их под неё незачем.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("n7.kcalai.web-tooling")
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
}
