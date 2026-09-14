plugins {
    id("n7.kcalai.android-library")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "n7.kcalai.feature.scanner"

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Валидатор контрольной цифры живёт там же, где остальная работа с продуктами:
    // код, не прошедший проверку, не должен покидать сканер.
    api(project(":core:repositories"))
    // Распознавание текста на этикетке: PP-OCRv5 через LiteRT.
    api(project(":core:ocr"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Только штрих-код. Текст на этикетке читает :core:ocr — ML Kit не умеет
    // кириллицу ни в одном из своих скриптов.
    implementation(libs.mlkit.barcode.scanning)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Распознавание проверяется только на устройстве: движок нативный, и подменить
    // его нечем — проверять разбор на выдуманных строках значит проверять разбор,
    // а не распознавание.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
