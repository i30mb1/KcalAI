plugins {
    id("n7.kcalai.kmp-library")
    id("n7.kcalai.web-target")
}

kotlin {
    android {
        namespace = "n7.kcalai.webapi"
    }

    sourceSets {
        commonMain.dependencies {
            // Приём пищи и суммы КБЖУ — те же типы по обе стороны сокета.
            api(project(":core:model"))
            // JSON через JsonElement API, как в `:core:nearby`: компиляторный плагин не нужен.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
