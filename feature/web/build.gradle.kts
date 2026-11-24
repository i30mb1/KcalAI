plugins {
    id("n7.kcalai.web-app")
}

kotlin {
    sourceSets {
        wasmJsMain.dependencies {
            // Тема, шрифты и персонажи — те же, что в приложении: страница выглядит как оно.
            implementation(project(":core:ui"))
            // Разговор с телефоном и что на странице видно — общий код, под тестами.
            implementation(project(":core:webapi"))
            implementation(libs.kotlinx.browser)
            implementation(libs.cmp.material3)
        }
    }
}

/*
 * Собранная страница — наружу, для `:core:webhost`: телефон отдаёт её из своих ресурсов.
 * Через конфигурацию, а не путь к чужой папке: Gradle сам соберёт страницу перед тем,
 * как её упаковывать, и не станет лезть в чужой проект во время настройки.
 */
val webDist = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")

// Имена собранных файлов — с хешем, и сборка складывает новые рядом со старыми.
// Без очистки в телефон ехали бы и все прошлые версии.
tasks.named("wasmJsBrowserDistribution") {
    // Своя ссылка на папку: лямбда, захватившая скрипт сборки, не ложится в кеш конфигурации.
    val dist = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
    doFirst { dist.get().asFile.deleteRecursively() }
}

val webBundle by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "kcal-web-bundle")) }
}

artifacts {
    add(webBundle.name, webDist) {
        builtBy("wasmJsBrowserDistribution")
    }
}
