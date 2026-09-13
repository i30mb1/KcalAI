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
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.mlkit.text.recognition)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
