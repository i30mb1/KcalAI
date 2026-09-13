plugins {
    id("n7.kcalai.android-library")
}

android {
    namespace = "n7.kcalai.repositories"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:fooddb"))
    api(project(":core:database"))
    api(project(":core:personal"))
    api(libs.kotlinx.coroutines.core)
}
