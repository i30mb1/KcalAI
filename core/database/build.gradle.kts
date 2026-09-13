plugins {
    id("n7.kcalai.android-library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "n7.kcalai.database"
}

// ksp — расширение верхнего уровня, не часть блока android
ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.room.runtime)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)
}
