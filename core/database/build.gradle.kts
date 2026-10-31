plugins {
    id("n7.kcalai.kmp-library")
    alias(libs.plugins.ksp)
}

kotlin {
    android {
        namespace = "n7.kcalai.database"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(libs.androidx.room.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}

// ksp — расширение верхнего уровня, не часть блока kotlin
ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    // Room генерирует реализацию базы под каждую цель отдельно.
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
