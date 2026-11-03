import java.util.Properties

plugins {
    id("n7.kcalai.android-library")
}

// Имя файла и длина отпечатка — из того же места, откуда их берут пак и tools/embeddb.
val model = Properties().apply { rootProject.file("tools/embeddb/model.properties").reader().use(::load) }

android {
    namespace = "n7.kcalai.semantic"

    defaultConfig {
        buildConfigField("String", "MODEL_FILE", "\"${model.getProperty("file")}\"")
        buildConfigField("String", "MODEL_SHA256", "\"${model.getProperty("sha256")}\"")
        buildConfigField("int", "MODEL_DIM", model.getProperty("dim"))
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
    testImplementation(libs.kotlinx.coroutines.test)
}
