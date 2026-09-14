plugins {
    id("n7.kcalai.android-library")
}

/**
 * Библиотеки LiteRT приходят обычной зависимостью — версия живёт в каталоге,
 * бинарников в репозитории нет. Но CMake нужен путь к `libLiteRt.so` на этапе
 * компоновки, а Gradle нативные библиотеки из AAR в CMake не отдаёт: для этого
 * в артефакте должен быть каталог `prefab`, а его там нет.
 *
 * Отсюда отдельная конфигурация: тот же артефакт резолвится второй раз, задача
 * распаковывает из него `jni/` в `build/`, и путь уезжает в CMake аргументом.
 * Ручных шагов нет, версия одна.
 */
val litertAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val litertJniDir: Provider<Directory> = layout.buildDirectory.dir("litert/jni")

val unpackLiteRtJni by tasks.registering(Sync::class) {
    description = "Распаковывает нативные библиотеки LiteRT из AAR для компоновки в CMake"
    from({ litertAar.map { zipTree(it) } }) {
        include("jni/**")
        // Каталог `jni` нужен только внутри AAR — CMake ждёт <abi>/lib*.so.
        eachFile { path = path.removePrefix("jni/") }
        includeEmptyDirs = false
    }
    into(litertJniDir)
}

android {
    namespace = "n7.kcalai.ocr"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        // В AAR ровно два ABI: телефоны и эмулятор. Релизные флаги CMake
        // привязаны к arm64, поэтому armeabi-v7a сюда не добавить просто так —
        // на таких телефонах OCR остаётся недоступным, см. LabelOcr.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DLITERT_JNI_DIR=${litertJniDir.get().asFile.absolutePath}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Модели уже сжаты, а распаковка при каждом чтении стоит времени на старте.
    androidResources {
        noCompress += listOf("tflite")
    }
}

// Нативная сборка обязана видеть распакованные .so до своего запуска.
tasks.withType<com.android.build.gradle.tasks.ExternalNativeBuildTask>().configureEach {
    dependsOn(unpackLiteRtJni)
}
tasks.withType<com.android.build.gradle.tasks.ExternalNativeBuildJsonTask>().configureEach {
    dependsOn(unpackLiteRtJni)
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    // Упаковку .so в APK берёт на себя AGP по этой зависимости.
    implementation(libs.litert)
    litertAar(libs.litert)
}
