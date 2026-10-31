plugins {
    id("n7.kcalai.kmp-compose")
}

kotlin {
    android {
        namespace = "n7.kcalai.feature.scanner"
        // Без этого в тестовый APK не попадают assets — фотографии этикеток.
        androidResources { enable = true }

        // Распознавание проверяется только на устройстве: движок нативный, и подменить
        // его нечем — проверять разбор на выдуманных строках значит проверять разбор,
        // а не распознавание.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Валидатор контрольной цифры живёт там же, где остальная работа с продуктами:
            // код, не прошедший проверку, не должен покидать сканер.
            api(project(":core:repositories"))
            // Палитра и типографика: сканер — такой же экран приложения, а не системный
            // оверлей, и выглядеть должен так же.
            api(project(":core:ui"))

            implementation(libs.cmp.material3)
            implementation(libs.cmp.material.icons.extended)
            implementation(libs.cmp.lifecycle.runtime.compose)
        }
        androidMain.dependencies {
            // Распознавание текста на этикетке: PP-OCRv5 через LiteRT.
            implementation(project(":core:ocr"))

            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)

            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.compose)
            // Только штрих-код. Текст на этикетке читает :core:ocr — ML Kit не умеет
            // кириллицу ни в одном из своих скриптов.
            implementation(libs.mlkit.barcode.scanning)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(project(":core:ocr"))
            implementation(libs.androidx.junit)
            implementation(libs.androidx.test.runner)
        }
    }
}
