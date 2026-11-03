import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // Схема AppFunctions — инструментов нейросети — генерируется из KDoc на сборке.
    alias(libs.plugins.ksp)
}

// Адрес локального сервера — в local.properties (не в git): kcal.server=http://192.168.1.10:8080
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "n7.kcalai"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "n7.kcalai"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1"

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
            // R8 в полном режиме: минификация, обфускация, оптимизация кода и удаление
            // неиспользуемых ресурсов — в DSL AGP 9.3 всё это включает один флаг.
            // Что вызывается по имени (JNI распознавания), держат правила в src/main/keepRules.
            optimization {
                enable = true
            }
        }
        // Release, каким его получат люди, — для проверки на устройстве (:smoke).
        // R8 ломает то, что библиотеки вызывают по имени, и видно это только в
        // минифицированной сборке. Подписана отладочным ключом и ставится под своим
        // id рядом с настоящим приложением: после прогона тест удаляет установленное,
        // и дневник в n7.kcalai не задет.
        create("minified") {
            initWith(getByName("release"))
            applicationIdSuffix = ".minified"
            signingConfig = signingConfigs.getByName("debug")
            // :core:ocr — обычная Android-библиотека, у неё только debug и release.
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            // Compose Resources из KMP-библиотек приезжают дважды: в assets, откуда
            // их и читают на Android, и Java-ресурсами. Вторая копия — лишние сотни КБ.
            excludes += "composeResources/**"
        }
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
    // Gemini Nano на телефоне: разбор фраз, вопросы к дневнику, второе мнение по этикетке.
    implementation(project(":core:assistant"))
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)
    implementation(project(":core:remote"))
    implementation(project(":core:ui"))
    implementation(project(":feature:diary"))
    // Мозг персонажей: фоновая работа пишет уведомления от их имени.
    implementation(project(":core:mascot"))

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
