import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // Схема AppFunctions — инструментов нейросети — генерируется из KDoc на сборке.
    alias(libs.plugins.ksp)
    alias(libs.plugins.play.publisher)
}

// Адрес сервера разработчика — в local.properties (не в git): kcal.server=http://<внешний IP>:8080
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
        versionCode = 7
        versionName = "1.4"

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
        // Material здесь только родитель стартовой темы. Его правила и правила CoordinatorLayout
        // держат то, что читается по имени из XML-разметки и в AppCompatActivity, а ни того
        // ни другого в приложении нет. Одно и то же правило приносят оба: указывать надо обоих.
        val rulesForXmlLayouts = arrayOf(
            "com.google.android.material:material",
            "androidx.coordinatorlayout:coordinatorlayout",
        )

        debug {
            buildConfigField("String", "KCAL_SERVER", "\"${localProperties.getProperty("kcal.server", "")}\"")
            // Разработчику всё уходит само, со съёмками этикеток.
            buildConfigField("boolean", "KCAL_SILENT_UPLOADS", "true")
        }
        // Сервер для людей — только по https: адрес в ~/.gradle/gradle.properties
        // (kcal.server=https://…), в публичный репозиторий он не попадает. Продукты
        // уходят только с согласия — их предлагает отправить Яйцо, — а съёмки этикеток
        // не уходят вовсе. Без адреса всё остаётся на устройстве.
        release {
            val releaseServer = providers.gradleProperty("kcal.server").orNull.orEmpty()
            require(releaseServer.isEmpty() || releaseServer.startsWith("https://")) {
                "kcal.server для release — только https://, а не $releaseServer"
            }
            buildConfigField("String", "KCAL_SERVER", "\"$releaseServer\"")
            buildConfigField("boolean", "KCAL_SILENT_UPLOADS", "false")
            signingConfig = uploadSigning
            // R8 в полном режиме: минификация, обфускация, оптимизация кода и удаление
            // неиспользуемых ресурсов — в DSL AGP 9.3 всё это включает один флаг.
            // Что вызывается по имени (JNI распознавания), держат правила в src/main/keepRules.
            optimization {
                enable = true
                keepRules {
                    ignoreFrom(*rulesForXmlLayouts)
                }
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
            // keepRules через initWith не переходят, а эта сборка обязана совпадать с release.
            optimization {
                keepRules {
                    ignoreFrom(*rulesForXmlLayouts)
                }
            }
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

    // Модель поиска по смыслу: в AAB отдельным fast-follow паком, в APK её нет.
    assetPacks += ":assets:embedding"
}

// Публикация: :app:publishReleaseBundle выкладывает подписанный AAB в production на всех сразу.
// Заметка к версии — в src/main/play/release-notes. Страницу в Play эта задача не трогает.
play {
    serviceAccountCredentials.set(rootProject.file("play-service-account.json"))
    defaultToAppBundles.set(true)
    track.set("production")
    releaseStatus.set(ReleaseStatus.COMPLETED)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:fooddb"))
    implementation(project(":core:database"))
    implementation(project(":core:repositories"))
    implementation(project(":core:resolver"))
    // Поиск по смыслу, когда поиск по словам не нашёл ничего: «барщ», «кофеёк с молочком».
    implementation(project(":core:semantic"))
    // Gemini Nano на телефоне: разбор фраз, вопросы к дневнику, второе мнение по этикетке.
    implementation(project(":core:assistant"))
    implementation(libs.androidx.appfunctions)
    implementation(libs.health.connect)
    // Своя запись шагов телефона (Recording API): в фоне, без аккаунта, данные остаются на телефоне.
    implementation(libs.play.services.fitness)
    ksp(libs.androidx.appfunctions.compiler)
    implementation(project(":core:remote"))
    implementation(project(":core:ui"))
    implementation(project(":feature:diary"))
    // Мозг персонажей: фоновая работа пишет уведомления от их имени.
    implementation(project(":core:mascot"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    // Граф зависимостей — общий с iOS; здесь только то, что у Android своё.
    implementation(project(":core:graph"))
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
