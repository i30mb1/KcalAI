plugins {
    id("n7.kcalai.kmp-library")
    id("n7.kcalai.web-target")
}

kotlin {
    android {
        namespace = "n7.kcalai.model"
    }

    sourceSets {
        commonMain.dependencies {
            // Даты подписей — «вторник, 24 нояб» — одни на приложение и страницу дневника.
            api(libs.kotlinx.datetime)
        }
    }
}
