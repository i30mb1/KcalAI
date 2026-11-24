plugins {
    id("n7.kcalai.kmp-library")
}

/**
 * Граф зависимостей приложения — один на Android и iOS.
 *
 * Что с чем соединено, решается здесь: цепочка источников, репозитории, мозг
 * персонажей, экранная модель дневника. Платформа даёт только своё — базу, путь
 * к справочнику, адрес сервера, поиск соседей, шторку — и наследует [n7.kcalai.graph.AppGraph].
 */
kotlin {
    android {
        namespace = "n7.kcalai.graph"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:fooddb"))
            api(project(":core:database"))
            api(project(":core:repositories"))
            api(project(":core:resolver"))
            api(project(":core:remote"))
            api(project(":core:personal"))
            api(project(":core:assistant"))
            api(project(":core:mascot"))
            api(project(":core:nearby"))
            // Страница дневника для браузера — на том же сервере, что приёмник соседей.
            api(project(":core:webhost"))
            api(project(":feature:diary"))
            implementation(libs.androidx.sqlite.bundled)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
