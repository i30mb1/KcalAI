pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Страница дневника на Kotlin/Wasm: сборке нужны Node.js, Yarn и Binaryen.
        // Плагин Kotlin объявил бы их сам, в проекте, — а репозитории здесь только общие.
        // Свой адрес у каждого и только для своего модуля: больше отсюда не берётся ничего.
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist") {
                    name = "Node.js"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/yarnpkg/yarn/releases/download") {
                    name = "Yarn"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.yarnpkg", "yarn") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/WebAssembly/binaryen/releases/download") {
                    name = "Binaryen"
                    patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

// Без пробела: сборка страницы под Kotlin/Wasm кладёт имя в package.json, а npm пробелов в нём не принимает.
rootProject.name = "KcalAI"
include(":app")
// Оболочка для iOS: отдаёт Xcode фреймворк KcalApp. На Android оболочка своя — `:app`.
include(":app-ios")
include(":core:model")
include(":core:fooddb")
include(":core:database")
include(":core:ocr")
include(":core:ui")
include(":core:personal")
include(":core:mascot")
include(":core:repositories")
include(":core:resolver")
include(":core:assistant")
include(":core:semantic")
include(":assets:embedding")
include(":core:remote")
// Телефон рядом: обнаружение в общей сети и предложение записи соседу.
include(":core:nearby")
include(":feature:diary")
// Граф зависимостей — один на Android и iOS; приложения дают только своё.
include(":core:graph")
include(":feature:scanner")
include(":smoke")

// Сервер — отдельная сборка: своя JVM-цель, свой Ktor. Включён сюда, чтобы Android Studio
// видела его и запускала конфигурацией «Сервер» (`.run/`): `:server:run`.
includeBuild("server")
include(":core:webapi")
include(":core:webhost")
include(":feature:web")
