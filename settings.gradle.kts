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
    }
}

rootProject.name = "Kcal AI"
include(":app")
include(":core:model")
include(":core:fooddb")
include(":core:database")
include(":core:ocr")
include(":core:ui")
include(":core:personal")
include(":core:repositories")
include(":core:resolver")
include(":core:remote")
include(":feature:diary")
include(":feature:scanner")
