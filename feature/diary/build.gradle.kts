plugins {
    id("n7.kcalai.android-library")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "n7.kcalai.feature.diary"

    // Compose-компилятор идёт внутри встроенного в AGP 9 Kotlin.
    // Плагин org.jetbrains.kotlin.plugin.compose подключать не нужно и нельзя.
    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:personal"))
    api(project(":core:repositories"))
    api(project(":core:resolver"))
    // Сканер — оверлей поверх дневника, а не отдельный экран: точка входа в него
    // живёт в строке ввода, и разводить их по разным местам незачем.
    api(project(":feature:scanner"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
