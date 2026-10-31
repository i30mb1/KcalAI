plugins {
    id("n7.kcalai.kmp-library")
}

kotlin {
    android {
        namespace = "n7.kcalai.resolver"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:repositories"))
        }
    }
}
