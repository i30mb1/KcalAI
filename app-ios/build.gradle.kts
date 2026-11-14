plugins {
    id("n7.kcalai.ios-app")
}

/**
 * Приложение для iOS: собирает граф зависимостей и отдаёт Swift единственную
 * точку входа — контроллер с дневником.
 *
 * Отдельным модулем, а не частью `:app`: тот — приложение Android со своим
 * манифестом, воркерами и Play. Общее у них всё содержательное, и оно уже
 * лежит в `core` и `feature`.
 */
kotlin {
    sourceSets {
        iosMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:fooddb"))
            implementation(project(":core:database"))
            implementation(project(":core:repositories"))
            implementation(project(":core:resolver"))
            implementation(project(":core:remote"))
            implementation(project(":core:personal"))
            // Нейросеть Apple на телефоне: разбор фраз и вопросы к дневнику.
            implementation(project(":core:assistant"))
            implementation(project(":core:mascot"))
            implementation(project(":core:ui"))
            implementation(project(":feature:diary"))

            // Драйвер справочника создаётся здесь, в месте сборки графа — как в `:app`.
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.cmp.ui)
        }
    }
}
