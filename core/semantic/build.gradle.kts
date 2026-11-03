import java.util.Properties

plugins {
    id("n7.kcalai.android-library")
}

// Имя файла модели — из того же места, откуда его берут пак и tools/embeddb.
val model = Properties().apply { rootProject.file("tools/embeddb/model.properties").reader().use(::load) }

android {
    namespace = "n7.kcalai.semantic"

    defaultConfig {
        buildConfigField("String", "MODEL_FILE", "\"${model.getProperty("file")}\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:repositories"))
    implementation(libs.litertlm.android)
    implementation(libs.play.asset.delivery.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
