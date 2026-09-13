plugins {
    id("n7.kcalai.android-library")
}

android {
    namespace = "n7.kcalai.resolver"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:repositories"))
}
