plugins {
    alias(libs.plugins.android.test)
}

/**
 * Сборка для людей глазами человека: открывается ли то, что он открывает.
 *
 * R8 переименовывает и вырезает то, что библиотеки вызывают по имени, и ломается
 * это только в минифицированной сборке — в debug R8 нет. Тест ставит `:app` типа
 * minified (release, подписанный отладочным ключом, под id n7.kcalai.minified)
 * и водит его снаружи через UiAutomator. Только эмулятор или тестовый телефон:
 *
 *     ANDROID_SERIAL=emulator-5554 ./gradlew :smoke:connectedMinifiedAndroidTest
 */
android {
    namespace = "n7.kcalai.smoke"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("minified") {
            signingConfig = signingConfigs.getByName("debug")
            // :core:ocr в графе :app — обычная Android-библиотека с debug и release.
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    targetProjectPath = ":app"
    // Тест — свой процесс: падение приложения не роняет тест, а становится его
    // результатом, и приложению не нужно быть отладочным.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    // Только минифицированная сборка: debug собран без R8, а release ставится под
    // id настоящего приложения — после прогона тест удалил бы его вместе с дневником.
    beforeVariants { it.enable = it.buildType == "minified" }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
}
