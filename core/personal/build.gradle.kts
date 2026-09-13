plugins {
    id("n7.kcalai.android-library")
}

android {
    namespace = "n7.kcalai.personal"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:database"))
    api(libs.kotlinx.coroutines.core)
}
