plugins {
    id("n7.kcalai.kmp-library")
}

kotlin {
    android {
        namespace = "n7.kcalai.repositories"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:fooddb"))
            api(project(":core:database"))
            api(project(":core:personal"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
