plugins {
    id("n7.kcalai.android-library")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "n7.kcalai.ui"

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Всё здесь — api: модуль существует ровно затем, чтобы экраны получили
    // палитру, типографику и сам Compose одной зависимостью.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.material3)

    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
