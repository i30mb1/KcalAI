import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Адрес локального сервера — в local.properties (не в git): kcal.server=http://192.168.1.10:8080
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "n7.kcalai"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "n7.kcalai"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Ключ загрузки в Google Play лежит вне репозитория, пути и пароли — в ~/.gradle/gradle.properties.
    // Без них release собирается неподписанным: сборке это не мешает, мешает только загрузке.
    val uploadStoreFile = providers.gradleProperty("kcal.upload.storeFile").orNull
    val uploadSigning = uploadStoreFile?.let {
        signingConfigs.create("upload") {
            storeFile = file(it)
            storePassword = providers.gradleProperty("kcal.upload.storePassword").get()
            keyAlias = providers.gradleProperty("kcal.upload.keyAlias").get()
            keyPassword = providers.gradleProperty("kcal.upload.keyPassword").get()
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "KCAL_SERVER", "\"${localProperties.getProperty("kcal.server", "")}\"")
        }
        // Локальный сервер — домашняя сеть разработчика и http без TLS: в сборке для людей
        // ему не место. Без адреса вклады и сессии съёмки остаются на устройстве.
        release {
            buildConfigField("String", "KCAL_SERVER", "\"\"")
            signingConfig = uploadSigning
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    androidResources {
        // seed.db копируется из assets потоком; несжатый ассет отдаёт честную длину,
        // по которой SeedInstaller решает, нужно ли переустанавливать копию.
        noCompress += "db"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:fooddb"))
    implementation(project(":core:database"))
    implementation(project(":core:repositories"))
    implementation(project(":core:resolver"))
    implementation(project(":core:remote"))
    implementation(project(":core:ui"))
    implementation(project(":feature:diary"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    // Драйвер справочника создаётся здесь, в месте сборки графа зависимостей.
    implementation(libs.androidx.sqlite.bundled)
    // Отправка вкладов переживает потерю сети, закрытие приложения и перезагрузку.
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
