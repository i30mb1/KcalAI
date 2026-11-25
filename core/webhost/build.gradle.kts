plugins {
    // Compose — ради Compose Resources: в них едет собранная страница, одинаково на обе платформы.
    id("n7.kcalai.kmp-compose")
}

kotlin {
    android {
        namespace = "n7.kcalai.webhost"
        androidResources { enable = true }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:webapi"))
            // Сервер тот же, что у приёмника соседей: один порт на телефон.
            api(project(":core:nearby"))
            api(project(":core:database"))
            api(project(":core:repositories"))
            api(project(":core:resolver"))
            api(project(":core:personal"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.websockets)
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.resources)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
        }
    }
}

compose.resources {
    packageOfResClass = "n7.kcalai.webhost.resources"
}

/*
 * Собранная страница из `:feature:web` — в Compose Resources: в APK и в бандл iOS
 * она едет одним путём, и читает её общий код, без платформенных адаптеров.
 */
val webBundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "kcal-web-bundle")) }
}

dependencies {
    webBundle(project(":feature:web"))
}

val syncWebBundle by tasks.registering(Sync::class) {
    from(webBundle)
    // Карты исходников нужны только разработчику страницы, а весят полтора мегабайта.
    exclude("**/*.map")
    // ПРОБА: прототип прогулки (papertrails) — рядом со страницей, по адресу /walk/kcal.html
    from(rootProject.file("prototypes/papertrails")) {
        include("kcal.html", "kcal/**", "src/**", "public/**", "LICENSE")
        into("walk")
    }
    into(layout.buildDirectory.dir("webBundle/files/web"))
}

compose.resources {
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = syncWebBundle.map { layout.buildDirectory.dir("webBundle").get() },
    )
}

// Плагин Compose копирует ресурсы в ассеты Android, не удаляя прежних, а имена файлов
// страницы меняются с каждой сборкой: без очистки в APK копились бы все прошлые версии.
tasks.matching { it.name == "copyAndroidMainComposeResourcesToAndroidAssets" }.configureEach {
    val copied = layout.buildDirectory.dir("generated/assets/copyAndroidMainComposeResourcesToAndroidAssets")
    doFirst { copied.get().asFile.deleteRecursively() }
}
