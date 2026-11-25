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
            // Иконки — векторами: символы вроде «⚙» браузер рисует цветным эмодзи.
            implementation(libs.cmp.material.icons.core)
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

/*
 * Прогулка идёт под страницей (index.html), поэтому холст Compose прозрачен везде, где страница
 * ничего не рисует. Skiko же в начале каждого кадра заливает его белым (CanvasRenderer:
 * `canvas.clear(Color.WHITE)`) — эту заливку подменяем прозрачной. Наш UI `clear()` не зовёт,
 * так что меняется только она. Не нашлась строка — новый Skiko: сборка падает, а не белеет молча.
 */
tasks.matching { it.name == "processSkikoRuntimeForKWasm" }.configureEach {
    val skiko = layout.buildDirectory.file("compose/skiko-runtime-processed-wasmjs/skiko.mjs")
    doLast {
        val file = skiko.get().asFile
        val clear = "org_jetbrains_skia_Canvas__1nClear"
        val white = "export let $clear = (...a) => ($clear = loadedWasm._[\"$clear\"])(...a)"
        val clean = "export let $clear = (canvas, color) => loadedWasm._[\"$clear\"](canvas, color === -1 ? 0 : color)"
        val text = file.readText()
        if (clean !in text) {
            check(white in text) { "Skiko сменил $clear — холст страницы снова зальётся белым, поправь замену" }
            file.writeText(text.replace(white, clean))
        }
    }
}
