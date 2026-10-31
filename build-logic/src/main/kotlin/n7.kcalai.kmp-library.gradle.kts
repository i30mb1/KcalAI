plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    android {
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
